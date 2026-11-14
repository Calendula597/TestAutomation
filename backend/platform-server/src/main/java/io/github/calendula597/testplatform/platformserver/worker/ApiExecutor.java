package io.github.calendula597.testplatform.platformserver.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.environment.Environment;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentMapper;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentVersion;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentVersionMapper;
import io.github.calendula597.testplatform.platformserver.environment.SecretCryptoService;
import io.github.calendula597.testplatform.platformserver.environment.TargetAllowlist;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersion;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersionMapper;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API single-request executor run inside the main Worker. The execution
 * chain: resolve the immutable case version and environment snapshot,
 * substitute plain variables, decrypt secrets only here at execution time,
 * enforce the target allowlist before the first byte and re-validate every
 * redirect hop, run real assertions, then mask and truncate everything that
 * will be stored or displayed.
 *
 * Assertion failures are ASSERTION_FAILED; infrastructure problems
 * (unreachable target, missing configuration) are ERROR. The executor
 * never reports PASSED for a request that did not actually happen.
 */
@Component
public class ApiExecutor {

    private static final int MAX_REDIRECT_HOPS = 5;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_BODY_CHARS = 32_000;
    private static final String MASK = "******";

    private final TestCaseVersionMapper versionMapper;
    private final EnvironmentMapper environmentMapper;
    private final EnvironmentVersionMapper environmentVersionMapper;
    private final SecretCryptoService cryptoService;
    private final TargetAllowlist targetAllowlist;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(REQUEST_TIMEOUT)
            .build();

    public ApiExecutor(TestCaseVersionMapper versionMapper, EnvironmentMapper environmentMapper,
                       EnvironmentVersionMapper environmentVersionMapper, SecretCryptoService cryptoService,
                       TargetAllowlist targetAllowlist, ObjectMapper objectMapper) {
        this.versionMapper = versionMapper;
        this.environmentMapper = environmentMapper;
        this.environmentVersionMapper = environmentVersionMapper;
        this.cryptoService = cryptoService;
        this.targetAllowlist = targetAllowlist;
        this.objectMapper = objectMapper;
    }

    public record ExecutionOutcome(String status, String reason, Map<String, Object> request,
                                   Map<String, Object> response, List<Map<String, Object>> assertions,
                                   long durationMs, List<String> redirects) {
    }

    public ExecutionOutcome execute(long caseVersionId, long environmentId) {
        try {
            return doExecute(caseVersionId, environmentId);
        } catch (ExecutionErrorException e) {
            return new ExecutionOutcome(ExecutionJob.STATUS_ERROR, e.getMessage(), null, null, List.of(), 0, List.of());
        } catch (Exception e) {
            return new ExecutionOutcome(ExecutionJob.STATUS_ERROR,
                    "执行器异常: " + e.getMessage(), null, null, List.of(), 0, List.of());
        }
    }

    private ExecutionOutcome doExecute(long caseVersionId, long environmentId) {
        TestCaseVersion version = versionMapper.selectById(caseVersionId);
        if (version == null || !TestCaseVersion.STATUS_READY.equals(version.getStatus())
                || !"API".equals(version.getCaseType()) || version.getExecutionJson() == null) {
            throw new ExecutionErrorException("用例版本不可执行：必须是已发布且携带执行配置的 API 用例");
        }
        JsonNode spec = readJson(version.getExecutionJson());
        Environment environment = environmentMapper.selectById(environmentId);
        if (environment == null) {
            throw new ExecutionErrorException("环境不存在");
        }
        EnvironmentVersion envVersion = environmentVersionMapper.selectOne(
                new LambdaQueryWrapper<EnvironmentVersion>()
                        .eq(EnvironmentVersion::getEnvironmentId, environmentId)
                        .eq(EnvironmentVersion::getVersion, environment.getCurrentVersion()));
        if (envVersion == null) {
            throw new ExecutionErrorException("环境版本数据缺失");
        }
        List<String> targets = readStringList(envVersion.getTargets());
        Map<String, String> variables = readStringMap(envVersion.getPlainVariables());
        Map<String, String> secrets = readStringMap(envVersion.getSecrets());

        JsonNode requestSpec = spec.path("request");
        String method = requestSpec.path("method").asText("").toUpperCase();
        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD").contains(method)) {
            throw new ExecutionErrorException("不支持的 HTTP 方法: " + method);
        }
        String url = substitute(requestSpec.path("url").asText(""), variables);
        Map<String, String> headers = new LinkedHashMap<>();
        requestSpec.path("headers").properties().forEach(entry ->
                headers.put(entry.getKey(), substitute(String.valueOf(entry.getValue()), variables)));
        String bodyType = requestSpec.path("bodyType").asText("JSON").toUpperCase();
        String body = requestSpec.has("body")
                ? substitute(requestSpec.path("body").asText(""), variables) : null;

        // Secrets are decrypted here, at execution time, and never stored.
        List<String> sensitiveValues = new ArrayList<>();
        applyAuthentication(requestSpec.path("auth"), secrets, headers, sensitiveValues);

        // Pre-execution gate: the first hop must be inside the allowlist.
        List<String> redirectHops = new ArrayList<>();
        TargetAllowlist.Check firstCheck = targetAllowlist.check(targets, url);
        if (!firstCheck.allowed()) {
            throw new ExecutionErrorException(firstCheck.reason() + ": " + url);
        }

        URI current = URI.create(url);
        byte[] responseBody = null;
        int statusCode = -1;
        Map<String, List<String>> responseHeaders = Map.of();
        long start = System.nanoTime();
        for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                        .timeout(REQUEST_TIMEOUT)
                        .method(method, body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                headers.forEach(builder::header);
                if (body != null && hop == 0) {
                    builder.header("Content-Type", switch (bodyType) {
                        case "TEXT" -> "text/plain";
                        case "FORM" -> "application/x-www-form-urlencoded";
                        default -> "application/json";
                    });
                }
                HttpResponse<byte[]> response = httpClient.send(builder.build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                statusCode = response.statusCode();
                responseHeaders = response.headers().map();
                responseBody = response.body();
                if (statusCode >= 300 && statusCode < 400) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    if (location == null) {
                        break;
                    }
                    URI next = current.resolve(location);
                    // Every redirect hop is re-validated as its own origin.
                    TargetAllowlist.Check hopCheck = targetAllowlist.check(targets, next.toString());
                    redirectHops.add(next.toString() + (hopCheck.allowed() ? " (允许)" : " (拒绝)"));
                    if (!hopCheck.allowed()) {
                        return errorOutcome("目标不在白名单: " + next, sensitiveValues, redirectHops,
                                elapsedMs(start), null);
                    }
                    current = next;
                    continue;
                }
                break;
            } catch (java.io.IOException e) {
                return errorOutcome("网络错误: " + e.getMessage(), sensitiveValues, redirectHops,
                        elapsedMs(start), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return errorOutcome("执行被中断", sensitiveValues, redirectHops, elapsedMs(start), null);
            }
        }
        if (statusCode == -1) {
            return errorOutcome("重定向次数超限", sensitiveValues, redirectHops, elapsedMs(start), null);
        }
        long durationMs = elapsedMs(start);

        String rawBody = responseBody == null ? "" : new String(responseBody, StandardCharsets.UTF_8);
        List<Map<String, Object>> assertions = runAssertions(spec.path("assertions"), rawBody,
                statusCode, durationMs);
        boolean allPassed = assertions.stream().allMatch(a -> Boolean.TRUE.equals(a.get("passed")));

        Map<String, Object> maskedRequest = maskRequest(method, url, headers, body, sensitiveValues);
        Map<String, Object> maskedResponse = maskResponse(statusCode, responseHeaders, rawBody, sensitiveValues);
        return new ExecutionOutcome(
                allPassed ? ExecutionJob.STATUS_PASSED : ExecutionJob.STATUS_ASSERTION_FAILED,
                null, maskedRequest, maskedResponse, assertions, durationMs, redirectHops);
    }

    private void applyAuthentication(JsonNode auth, Map<String, String> secrets,
                                     Map<String, String> headers, List<String> sensitiveValues) {
        if (auth == null || auth.isMissingNode() || auth.isNull()) {
            return;
        }
        String type = auth.path("type").asText("NONE").toUpperCase();
        switch (type) {
            case "BEARER" -> {
                String value = decryptSecret(auth.path("secretKey").asText(""), secrets, sensitiveValues);
                headers.put("Authorization", "Bearer " + value);
                sensitiveValues.add("Bearer " + value);
            }
            case "BASIC" -> {
                String username = decryptSecret(auth.path("usernameKey").asText(""), secrets, sensitiveValues);
                String password = decryptSecret(auth.path("passwordKey").asText(""), secrets, sensitiveValues);
                String composed = "Basic " + Base64.getEncoder()
                        .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", composed);
                sensitiveValues.add(composed);
            }
            case "API_KEY" -> {
                String value = decryptSecret(auth.path("secretKey").asText(""), secrets, sensitiveValues);
                headers.put(auth.path("headerName").asText("X-Api-Key"), value);
            }
            case "NONE" -> {
                // No authentication.
            }
            default -> throw new ExecutionErrorException("不支持的认证类型: " + type);
        }
    }

    private String decryptSecret(String key, Map<String, String> secrets, List<String> sensitiveValues) {
        if (key.isBlank() || !secrets.containsKey(key)) {
            throw new ExecutionErrorException("环境密钥不存在: " + key);
        }
        if (!cryptoService.isConfigured()) {
            throw new ExecutionErrorException("密钥加密未配置主密钥，无法解析密钥");
        }
        String value = cryptoService.decrypt(secrets.get(key));
        sensitiveValues.add(value);
        return value;
    }

    private List<Map<String, Object>> runAssertions(JsonNode assertions, String body,
                                                    int statusCode, long durationMs) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode node : assertions) {
            String type = node.path("type").asText("");
            String expected = node.path("expected").asText("");
            String actual;
            boolean passed;
            switch (type) {
                case "STATUS" -> {
                    actual = String.valueOf(statusCode);
                    passed = statusCode >= 0 && expected.equals(actual);
                }
                case "TEXT" -> {
                    actual = body;
                    passed = body.contains(expected);
                }
                case "JSONPATH" -> {
                    String path = node.path("path").asText("$");
                    JsonNode parsed = tryParse(body);
                    actual = parsed == null ? "" : resolveJsonPath(parsed, path);
                    passed = parsed != null && !actual.isEmpty() && actual.equals(expected);
                }
                case "RESPONSE_TIME_MS" -> {
                    actual = String.valueOf(durationMs);
                    try {
                        passed = durationMs <= Long.parseLong(expected);
                    } catch (NumberFormatException e) {
                        passed = false;
                    }
                }
                default -> {
                    actual = "";
                    passed = false;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            if (node.has("path")) {
                row.put("path", node.path("path").asText());
            }
            row.put("expected", expected);
            row.put("actual", truncate(String.valueOf(actual), 2_000));
            row.put("passed", passed);
            results.add(row);
        }
        return results;
    }

    /** Dot paths with array indexes: $.store.books[0].title */
    private String resolveJsonPath(JsonNode root, String path) {
        String normalized = path.startsWith("$") ? path.substring(1) : path;
        String[] segments = normalized.replace("[", ".").replace("]", "").split("\\.");
        JsonNode current = root;
        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }
            if (current.isArray()) {
                try {
                    current = current.get(Integer.parseInt(segment));
                } catch (NumberFormatException e) {
                    return "";
                }
            } else if (current.isObject()) {
                current = current.path(segment);
            } else {
                return "";
            }
            if (current == null || current.isMissingNode()) {
                return "";
            }
        }
        return current.isValueNode() ? current.asText() : current.toString();
    }

    private JsonNode tryParse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> maskRequest(String method, String url, Map<String, String> headers,
                                            String body, List<String> sensitiveValues) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("method", method);
        request.put("url", mask(url, sensitiveValues));
        Map<String, Object> maskedHeaders = new LinkedHashMap<>();
        headers.forEach((name, value) -> maskedHeaders.put(name, mask(value, sensitiveValues)));
        request.put("headers", maskedHeaders);
        if (body != null) {
            request.put("body", mask(body, sensitiveValues));
        }
        return request;
    }

    private Map<String, Object> maskResponse(int statusCode, Map<String, List<String>> headers,
                                             String body, List<String> sensitiveValues) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("statusCode", statusCode);
        Map<String, Object> maskedHeaders = new LinkedHashMap<>();
        headers.forEach((name, values) -> maskedHeaders.put(name,
                mask(String.join(", ", values), sensitiveValues)));
        response.put("headers", maskedHeaders);
        boolean truncated = body.length() > MAX_BODY_CHARS;
        response.put("body", mask(truncate(body, MAX_BODY_CHARS), sensitiveValues));
        response.put("bodyTruncated", truncated);
        return response;
    }

    private ExecutionOutcome errorOutcome(String reason, List<String> sensitiveValues,
                                          List<String> redirectHops, long durationMs,
                                          Map<String, Object> request) {
        return new ExecutionOutcome(ExecutionJob.STATUS_ERROR, reason, request, null,
                List.of(), durationMs, redirectHops);
    }

    private ExecutionOutcome errorOutcome(String reason, List<String> sensitiveValues,
                                          List<String> redirectHops, long durationMs) {
        return errorOutcome(reason, sensitiveValues, redirectHops, durationMs, null);
    }

    /** Replaces every secret occurrence in output artifacts with the mask,
     * longest values first so composed credentials are fully removed. */
    private String mask(String value, List<String> sensitiveValues) {
        String result = value == null ? "" : value;
        List<String> ordered = sensitiveValues.stream()
                .filter(secret -> secret != null && !secret.isEmpty())
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();
        for (String secret : ordered) {
            result = result.replace(secret, MASK);
        }
        return result;
    }

    private String substitute(String template, Map<String, String> variables) {
        if (template == null || !template.contains("{{")) {
            return template;
        }
        String result = template;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\{\\{([A-Za-z0-9_.-]+)}}").matcher(template);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!variables.containsKey(key)) {
                throw new ExecutionErrorException("变量未定义: " + key);
            }
            result = result.replace("{{" + key + "}}", variables.get(key));
        }
        return result;
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ExecutionErrorException("执行配置无法解析", e);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new ExecutionErrorException("环境目标数据反序列化失败", e);
        }
    }

    private Map<String, String> readStringMap(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (Exception e) {
            throw new ExecutionErrorException("环境快照反序列化失败", e);
        }
    }

    public static class ExecutionErrorException extends RuntimeException {
        public ExecutionErrorException(String message) {
            super(message);
        }

        public ExecutionErrorException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
