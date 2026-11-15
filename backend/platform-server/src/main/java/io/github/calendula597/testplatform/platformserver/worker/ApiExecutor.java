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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * API executor run inside the main Worker: single requests (T15 shape),
 * chained requests with response extraction, and data-row
 * parameterization with per-row results.
 *
 * Invariants carried over from the single-request loop: secrets are
 * decrypted only here at execution time and masked longest-first in every
 * stored artifact; the target allowlist gates the first request and every
 * redirect hop; assertion failures are ASSERTION_FAILED while
 * infrastructure problems are ERROR. Data rows may never carry a secret
 * value — pasting one aborts the row with an explicit reason. Execution
 * jobs are never retried automatically, so no request here can be
 * silently repeated by the platform.
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
                                   long durationMs, List<String> redirects, List<Map<String, Object>> rows) {
    }

    private record StepSpec(String name, JsonNode request, JsonNode extract, JsonNode assertions) {
    }

    public ExecutionOutcome execute(long caseVersionId, long environmentId) {
        try {
            return doExecute(caseVersionId, environmentId);
        } catch (ExecutionErrorException e) {
            return new ExecutionOutcome(ExecutionJob.STATUS_ERROR, e.getMessage(), null, null,
                    List.of(), 0, List.of(), List.of());
        } catch (Exception e) {
            return new ExecutionOutcome(ExecutionJob.STATUS_ERROR,
                    "执行器异常: " + e.getMessage(), null, null, List.of(), 0, List.of(), List.of());
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

        List<StepSpec> steps = spec.has("requests")
                ? parseSteps(spec.path("requests"))
                : List.of(new StepSpec("", spec.path("request"), spec.path("extract"),
                        spec.path("assertions")));
        boolean hasDataRows = spec.has("dataRows") && spec.path("dataRows").isArray()
                && !spec.path("dataRows").isEmpty();
        List<Map<String, String>> dataRows = new ArrayList<>();
        if (hasDataRows) {
            for (JsonNode row : spec.path("dataRows")) {
                dataRows.add(readStringMap(objectMapper.writeValueAsString(row)));
            }
        }

        List<String> targets = readStringList(envVersion.getTargets());
        Map<String, String> variables = readStringMap(envVersion.getPlainVariables());
        Map<String, String> secretCiphertexts = readStringMap(envVersion.getSecrets());
        // Every stored secret is decrypted once here, at execution time, and
        // drives masking for the whole run — whether or not a step uses it.
        if (!secretCiphertexts.isEmpty() && !cryptoService.isConfigured()) {
            throw new ExecutionErrorException("密钥加密未配置主密钥，无法解析密钥");
        }
        Map<String, String> secretPlain = new LinkedHashMap<>();
        List<String> sensitiveValues = new ArrayList<>();
        for (Map.Entry<String, String> entry : secretCiphertexts.entrySet()) {
            String plain = cryptoService.decrypt(entry.getValue());
            secretPlain.put(entry.getKey(), plain);
            sensitiveValues.add(plain);
        }

        // Legacy shape: exactly one request without data rows or extraction
        // keeps the single-request result layout (T15 consumers).
        if (steps.size() == 1 && !hasDataRows && steps.get(0).extract().isMissingNode()) {
            return runLegacySingle(steps.get(0), variables, targets, secretPlain, sensitiveValues);
        }

        // Chains and data-row cases always run in rows shape; a chain
        // without data rows still runs exactly once.
        if (!hasDataRows) {
            dataRows.add(Map.of());
        }

        List<Map<String, Object>> rowOutcomes = new ArrayList<>();
        long totalMs = 0;
        boolean anyError = false;
        boolean anyAssertionFailed = false;
        String firstErrorReason = null;
        for (int index = 0; index < dataRows.size(); index++) {
            Map<String, String> rowValues = dataRows.get(index);
            if (carriesSecret(rowValues, sensitiveValues)) {
                rowOutcomes.add(rowErrorOutcome(index, rowValues, sensitiveValues,
                        "数据行不得包含密钥明文"));
                anyError = true;
                if (firstErrorReason == null) {
                    firstErrorReason = "数据行不得包含密钥明文";
                }
                continue;
            }
            Map<String, String> rowVars = new LinkedHashMap<>(variables);
            rowVars.putAll(rowValues);
            RowRun run = runRow(steps, rowVars, targets, secretPlain, sensitiveValues);
            totalMs += run.durationMs();
            if (ExecutionJob.STATUS_ERROR.equals(run.status())) {
                anyError = true;
                if (firstErrorReason == null) {
                    firstErrorReason = run.reason();
                }
            } else if (ExecutionJob.STATUS_ASSERTION_FAILED.equals(run.status())) {
                anyAssertionFailed = true;
            }
            rowOutcomes.add(rowOutcome(index, rowValues, run, sensitiveValues));
        }
        String status = anyError ? ExecutionJob.STATUS_ERROR
                : anyAssertionFailed ? ExecutionJob.STATUS_ASSERTION_FAILED
                : ExecutionJob.STATUS_PASSED;
        return new ExecutionOutcome(status, firstErrorReason, null, null, List.of(), totalMs,
                List.of(), rowOutcomes);
    }

    private boolean carriesSecret(Map<String, String> rowValues, List<String> sensitiveValues) {
        for (String value : rowValues.values()) {
            if (sensitiveValues.contains(value)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Chain execution
    // ------------------------------------------------------------------

    private record RowRun(String status, String reason, long durationMs,
                          List<Map<String, Object>> steps) {
    }

    private RowRun runRow(List<StepSpec> steps, Map<String, String> vars, List<String> targets,
                          Map<String, String> secretPlain, List<String> sensitiveValues) {
        List<Map<String, Object>> stepOutcomes = new ArrayList<>();
        long rowMs = 0;
        String rowStatus = ExecutionJob.STATUS_PASSED;
        String rowReason = null;
        for (StepSpec step : steps) {
            Map<String, Object> outcome = runStep(step, vars, targets, secretPlain, sensitiveValues);
            stepOutcomes.add(outcome);
            rowMs += (long) outcome.getOrDefault("durationMs", 0L);
            String stepStatus = (String) outcome.get("status");
            if (ExecutionJob.STATUS_ERROR.equals(stepStatus)) {
                rowStatus = ExecutionJob.STATUS_ERROR;
                rowReason = (String) outcome.get("reason");
                break;
            }
            if (ExecutionJob.STATUS_ASSERTION_FAILED.equals(stepStatus)
                    && ExecutionJob.STATUS_PASSED.equals(rowStatus)) {
                rowStatus = ExecutionJob.STATUS_ASSERTION_FAILED;
            }
        }
        return new RowRun(rowStatus, rowReason, rowMs, stepOutcomes);
    }

    private Map<String, Object> runStep(StepSpec step, Map<String, String> vars, List<String> targets,
                                        Map<String, String> secretPlain, List<String> sensitiveValues) {
        JsonNode requestSpec = step.request();
        String method = requestSpec.path("method").asText("").toUpperCase();
        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD").contains(method)) {
            return stepErrorOutcome(step.name(), "不支持的 HTTP 方法: " + method);
        }
        String url = substitute(requestSpec.path("url").asText(""), vars);
        Map<String, String> headers = new LinkedHashMap<>();
        requestSpec.path("headers").properties().forEach(entry ->
                headers.put(entry.getKey(), substitute(entry.getValue().asText(""), vars)));
        String bodyType = requestSpec.path("bodyType").asText("JSON").toUpperCase();
        String body = requestSpec.has("body")
                ? substitute(requestSpec.path("body").asText(""), vars) : null;

        applyAuthentication(requestSpec.path("auth"), headers, secretPlain, sensitiveValues);

        TargetAllowlist.Check firstCheck = targetAllowlist.check(targets, url);
        if (!firstCheck.allowed()) {
            return stepErrorOutcome(step.name(), firstCheck.reason() + ": " + url);
        }

        URI current = URI.create(url);
        byte[] responseBody = null;
        int statusCode = -1;
        Map<String, List<String>> responseHeaders = Map.of();
        List<String> redirectHops = new ArrayList<>();
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
                    TargetAllowlist.Check hopCheck = targetAllowlist.check(targets, next.toString());
                    redirectHops.add(next.toString() + (hopCheck.allowed() ? " (允许)" : " (拒绝)"));
                    if (!hopCheck.allowed()) {
                        Map<String, Object> denied = stepErrorOutcome(step.name(),
                                "目标不在白名单: " + next);
                        denied.put("redirects", redirectHops);
                        return denied;
                    }
                    current = next;
                    continue;
                }
                break;
            } catch (java.io.IOException e) {
                return stepErrorOutcome(step.name(), "网络错误: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return stepErrorOutcome(step.name(), "执行被中断");
            }
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        if (statusCode == -1) {
            Map<String, Object> exceeded = stepErrorOutcome(step.name(), "重定向次数超限");
            exceeded.put("redirects", redirectHops);
            return exceeded;
        }

        String rawBody = responseBody == null ? "" : new String(responseBody, StandardCharsets.UTF_8);
        List<Map<String, Object>> assertions = runAssertions(step.assertions(), rawBody,
                statusCode, durationMs, vars);
        boolean allPassed = assertions.stream().allMatch(a -> Boolean.TRUE.equals(a.get("passed")));
        List<Map<String, Object>> extracted = runExtraction(step.extract(), rawBody, vars, sensitiveValues);

        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("name", step.name());
        outcome.put("status", allPassed ? ExecutionJob.STATUS_PASSED : ExecutionJob.STATUS_ASSERTION_FAILED);
        outcome.put("request", maskRequest(method, url, headers, body, sensitiveValues));
        outcome.put("response", maskResponse(statusCode, responseHeaders, rawBody, sensitiveValues));
        outcome.put("assertions", assertions);
        outcome.put("extract", extracted);
        outcome.put("durationMs", durationMs);
        outcome.put("redirects", redirectHops);
        return outcome;
    }

    private List<Map<String, Object>> runExtraction(JsonNode extractSpec, String body,
                                                    Map<String, String> vars, List<String> sensitiveValues) {
        List<Map<String, Object>> extracted = new ArrayList<>();
        if (extractSpec == null || extractSpec.isMissingNode() || !extractSpec.isArray()) {
            return extracted;
        }
        JsonNode parsed = tryParse(body);
        for (JsonNode rule : extractSpec) {
            String variable = rule.path("variable").asText("");
            String path = rule.path("path").asText("$");
            String type = rule.path("type").asText("JSONPATH");
            String value = "";
            if ("JSONPATH".equals(type)) {
                value = parsed == null ? "" : resolveJsonPath(parsed, path);
            }
            if (!variable.isBlank() && !value.isEmpty()) {
                vars.put(variable, value);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("variable", variable);
            row.put("value", mask(value, sensitiveValues));
            extracted.add(row);
        }
        return extracted;
    }

    private void applyAuthentication(JsonNode auth, Map<String, String> headers,
                                     Map<String, String> secretPlain, List<String> sensitiveValues) {
        if (auth == null || auth.isMissingNode() || auth.isNull()) {
            return;
        }
        String type = auth.path("type").asText("NONE").toUpperCase();
        switch (type) {
            case "BEARER" -> {
                String value = requireSecret(auth.path("secretKey").asText(""), secretPlain);
                headers.put("Authorization", "Bearer " + value);
                sensitiveValues.add("Bearer " + value);
            }
            case "BASIC" -> {
                String username = requireSecret(auth.path("usernameKey").asText(""), secretPlain);
                String password = requireSecret(auth.path("passwordKey").asText(""), secretPlain);
                String composed = "Basic " + Base64.getEncoder()
                        .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", composed);
                sensitiveValues.add(composed);
            }
            case "API_KEY" -> {
                String value = requireSecret(auth.path("secretKey").asText(""), secretPlain);
                headers.put(auth.path("headerName").asText("X-Api-Key"), value);
            }
            case "NONE" -> {
                // No authentication.
            }
            default -> throw new ExecutionErrorException("不支持的认证类型: " + type);
        }
    }

    private String requireSecret(String key, Map<String, String> secretPlain) {
        if (key.isBlank() || !secretPlain.containsKey(key)) {
            throw new ExecutionErrorException("环境密钥不存在: " + key);
        }
        return secretPlain.get(key);
    }

    private Map<String, Object> stepErrorOutcome(String name, String reason) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("name", name);
        outcome.put("status", ExecutionJob.STATUS_ERROR);
        outcome.put("reason", reason);
        outcome.put("assertions", List.of());
        outcome.put("extract", List.of());
        outcome.put("durationMs", 0L);
        return outcome;
    }

    private Map<String, Object> rowOutcome(int index, Map<String, String> rowValues, RowRun run,
                                           List<String> sensitiveValues) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rowIndex", index);
        Map<String, Object> maskedValues = new LinkedHashMap<>();
        rowValues.forEach((key, value) -> maskedValues.put(key, mask(value, sensitiveValues)));
        row.put("values", maskedValues);
        row.put("status", run.status());
        if (run.reason() != null) {
            row.put("reason", run.reason());
        }
        row.put("durationMs", run.durationMs());
        row.put("steps", run.steps());
        return row;
    }

    private Map<String, Object> rowErrorOutcome(int index, Map<String, String> rowValues,
                                                List<String> sensitiveValues, String reason) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rowIndex", index);
        Map<String, Object> maskedValues = new LinkedHashMap<>();
        rowValues.forEach((key, value) -> maskedValues.put(key, mask(value, sensitiveValues)));
        row.put("values", maskedValues);
        row.put("status", ExecutionJob.STATUS_ERROR);
        row.put("reason", reason);
        row.put("steps", List.of());
        return row;
    }

    // ------------------------------------------------------------------
    // Legacy single-request shape (T15)
    // ------------------------------------------------------------------

    private ExecutionOutcome runLegacySingle(StepSpec step, Map<String, String> variables,
                                             List<String> targets, Map<String, String> secretPlain,
                                             List<String> sensitiveValues) {
        Map<String, Object> outcome = runStep(step, new LinkedHashMap<>(variables), targets,
                secretPlain, sensitiveValues);
        String status = (String) outcome.get("status");
        String reason = (String) outcome.get("reason");
        @SuppressWarnings("unchecked")
        Map<String, Object> request = (Map<String, Object>) outcome.get("request");
        @SuppressWarnings("unchecked")
        Map<String, Object> response = (Map<String, Object>) outcome.get("response");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assertions = (List<Map<String, Object>>) outcome.get("assertions");
        @SuppressWarnings("unchecked")
        List<String> redirects = (List<String>) outcome.getOrDefault("redirects", List.of());
        return new ExecutionOutcome(status, reason, request, response, assertions,
                (long) outcome.getOrDefault("durationMs", 0L), redirects, List.of());
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    private List<StepSpec> parseSteps(JsonNode requests) {
        List<StepSpec> steps = new ArrayList<>();
        for (JsonNode node : requests) {
            steps.add(new StepSpec(node.path("name").asText(""), node.path("request"),
                    node.path("extract"), node.path("assertions")));
        }
        if (steps.isEmpty()) {
            throw new ExecutionErrorException("执行配置缺少请求步骤");
        }
        return steps;
    }

    private List<Map<String, Object>> runAssertions(JsonNode assertions, String body,
                                                    int statusCode, long durationMs,
                                                    Map<String, String> vars) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode node : assertions) {
            String type = node.path("type").asText("");
            String expected = substitute(node.path("expected").asText(""), vars);
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
