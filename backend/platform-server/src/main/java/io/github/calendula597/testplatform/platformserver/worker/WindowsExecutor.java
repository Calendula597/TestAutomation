package io.github.calendula597.testplatform.platformserver.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.environment.Environment;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentMapper;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentVersion;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentVersionMapper;
import io.github.calendula597.testplatform.platformserver.environment.SecretCryptoService;
import io.github.calendula597.testplatform.platformserver.reporting.EvidenceStorage;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersion;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersionMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
public class WindowsExecutor {

    private static final String ELEMENT_KEY = "element-6066-11e4-a52e-4f735466cecf";
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Za-z0-9_.-]+)}}");
    private static final String MASK = "******";

    private final TestCaseVersionMapper versionMapper;
    private final EnvironmentMapper environmentMapper;
    private final EnvironmentVersionMapper environmentVersionMapper;
    private final SecretCryptoService cryptoService;
    private final ObjectMapper objectMapper;
    private final EvidenceStorage evidenceStorage;
    private final String appiumBaseUrl;
    private final Path appiumHome;
    private final String powerShellPath;
    private final Path demoScript;
    private final Path demoExecutable;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public WindowsExecutor(TestCaseVersionMapper versionMapper, EnvironmentMapper environmentMapper,
                           EnvironmentVersionMapper environmentVersionMapper, SecretCryptoService cryptoService,
                           ObjectMapper objectMapper, EvidenceStorage evidenceStorage,
                           @Value("${app.worker.appium-base-url:http://127.0.0.1:4723}") String appiumBaseUrl,
                           @Value("${app.worker.appium-home:}") String appiumHome,
                           @Value("${app.worker.powershell-path:C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe}")
                           String powerShellPath,
                           @Value("${app.worker.windows-demo-script:}") String demoScript,
                           @Value("${app.worker.windows-demo-executable:}") String demoExecutable) {
        this.versionMapper = versionMapper;
        this.environmentMapper = environmentMapper;
        this.environmentVersionMapper = environmentVersionMapper;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
        this.evidenceStorage = evidenceStorage;
        this.appiumBaseUrl = appiumBaseUrl.replaceAll("/$", "");
        this.appiumHome = appiumHome == null || appiumHome.isBlank() ? null : Path.of(appiumHome);
        this.powerShellPath = powerShellPath;
        this.demoScript = demoScript == null || demoScript.isBlank() ? null : Path.of(demoScript);
        this.demoExecutable = demoExecutable == null || demoExecutable.isBlank() ? null : Path.of(demoExecutable);
    }

    public record WindowsOutcome(String status, String reason, List<Map<String, Object>> steps,
                                 List<Map<String, Object>> evidence, long durationMs,
                                 String appType, String driverName, String driverVersion) {
    }

    private record StepSpec(String action, String selector, String value) {
    }

    private static final class ControlException extends RuntimeException {
        private final String status;

        private ControlException(String status, String message) {
            super(message);
            this.status = status;
        }
    }

    public WindowsOutcome execute(long caseVersionId, long environmentId, ExecutionControl control) {
        Instant started = Instant.now();
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        String sessionId = null;
        Path runDirectory = null;
        String status = ExecutionJob.STATUS_PASSED;
        String reason = null;
        String appType = "WINFORMS";
        String driverVersion = "unknown";
        List<String> sensitiveValues = new ArrayList<>();
        try {
            TestCaseVersion version = versionMapper.selectById(caseVersionId);
            if (version == null || !TestCaseVersion.STATUS_READY.equals(version.getStatus())
                    || !"WINDOWS_NATIVE".equals(version.getCaseType()) || version.getExecutionJson() == null) {
                throw new ExecutionErrorException("用例版本不可执行：必须是已发布的原生 Windows 用例");
            }
            JsonNode spec = readJson(version.getExecutionJson());
            if (!"demo-winforms".equals(spec.path("app").asText())) {
                throw new ExecutionErrorException("仅支持显式选择的 WinForms 演示应用");
            }
            if (demoExecutable == null ? demoScript == null || !Files.isRegularFile(demoScript)
                    : !Files.isRegularFile(demoExecutable)) {
                throw new ExecutionErrorException("原生 Windows 演示程序未配置或不存在");
            }
            List<StepSpec> stepSpecs = parseSteps(spec.path("steps"));
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
            Map<String, String> variables = readStringMap(envVersion.getPlainVariables());
            Map<String, String> ciphertexts = readStringMap(envVersion.getSecrets());
            if (!ciphertexts.isEmpty() && !cryptoService.isConfigured()) {
                throw new ExecutionErrorException("密钥加密未配置主密钥");
            }
            for (Map.Entry<String, String> entry : ciphertexts.entrySet()) {
                String plain = cryptoService.decrypt(entry.getValue());
                variables.put(entry.getKey(), plain);
                sensitiveValues.add(plain);
            }

            runDirectory = evidenceStorage.createRunDirectory();
            Path userData = runDirectory.resolve("user-data");
            Files.createDirectories(userData);
            driverVersion = driverVersion();
            sessionId = createSession(userData);
            for (int index = 0; index < stepSpecs.size(); index++) {
                checkControl(control);
                StepSpec step = stepSpecs.get(index);
                long stepStarted = System.nanoTime();
                Map<String, Object> outcome = runStep(sessionId, step, variables, sensitiveValues, control);
                outcome.put("index", index);
                outcome.put("durationMs", (System.nanoTime() - stepStarted) / 1_000_000);
                steps.add(outcome);
                if (ExecutionJob.STATUS_ERROR.equals(outcome.get("status"))) {
                    status = ExecutionJob.STATUS_ERROR;
                    reason = String.valueOf(outcome.get("error"));
                    captureScreenshot(sessionId, runDirectory, evidence, "step-" + index + "-failure.png");
                    break;
                }
                if (ExecutionJob.STATUS_ASSERTION_FAILED.equals(outcome.get("status"))) {
                    status = ExecutionJob.STATUS_ASSERTION_FAILED;
                    reason = "原生 Windows 业务断言未满足";
                    captureScreenshot(sessionId, runDirectory, evidence, "step-" + index + "-failure.png");
                    break;
                }
            }
        } catch (ControlException e) {
            status = e.status;
            reason = e.getMessage();
        } catch (ExecutionErrorException e) {
            status = ExecutionJob.STATUS_ERROR;
            reason = e.getMessage();
        } catch (Exception e) {
            status = ExecutionJob.STATUS_ERROR;
            reason = "执行器异常: " + e.getMessage();
        } finally {
            if (sessionId != null) {
                captureScreenshot(sessionId, runDirectory, evidence, "final.png");
                deleteSession(sessionId);
            }
            if (runDirectory != null) {
                killAbandonedDemoProcesses();
                deleteUserData(runDirectory.resolve("user-data"));
            }
        }
        return new WindowsOutcome(status, mask(reason, sensitiveValues), steps, evidence,
                elapsed(started), appType, "Wincore", driverVersion);
    }

    private String createSession(Path userData) {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("platformName", "Windows");
        capabilities.put("appium:automationName", "Wincore");
        capabilities.put("appium:app", demoExecutable == null ? powerShellPath
                : demoExecutable.toAbsolutePath().normalize().toString());
        if (demoExecutable == null) {
            capabilities.put("appium:appArguments", "-NoProfile -ExecutionPolicy Bypass -File "
                    + demoScript.toAbsolutePath().normalize());
        }
        capabilities.put("appium:appEnvironment", Map.of("NATIVE_DEMO_USER_DATA", userData.toString()));
        capabilities.put("appium:shouldCloseApp", true);
        capabilities.put("appium:ms:forcequit", true);
        capabilities.put("appium:ms:waitForAppLaunch", 10);
        JsonNode response = request("POST", "/session", Map.of("capabilities",
                Map.of("alwaysMatch", capabilities)), null);
        String sessionId = response.path("sessionId").asText("");
        if (sessionId.isBlank()) {
            throw new ExecutionErrorException("WinCore 未返回会话 ID");
        }
        return sessionId;
    }

    private Map<String, Object> runStep(String sessionId, StepSpec step, Map<String, String> variables,
                                        List<String> sensitiveValues, ExecutionControl control) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("action", step.action());
        if (step.selector() != null) {
            outcome.put("selector", step.selector());
        }
        try {
            String value = substitute(step.value(), variables);
            switch (step.action()) {
                case "FILL" -> {
                    Map<String, Object> element = findElement(sessionId, step.selector());
                    execute(sessionId, "windows: setValue", List.of(element, value));
                    outcome.put("actual", mask(value, sensitiveValues));
                    outcome.put("status", ExecutionJob.STATUS_PASSED);
                }
                case "CLICK" -> {
                    Map<String, Object> element = findElement(sessionId, step.selector());
                    execute(sessionId, "windows: invoke", List.of(element));
                    outcome.put("actual", "clicked");
                    outcome.put("status", ExecutionJob.STATUS_PASSED);
                }
                case "ASSERT_TEXT" -> {
                    Map<String, Object> element = findElement(sessionId, step.selector());
                    String elementId = String.valueOf(element.get(ELEMENT_KEY));
                    String text = request("GET", "/session/" + sessionId + "/element/" + elementId + "/text",
                            null, sessionId).asText();
                    outcome.put("expected", mask(value, sensitiveValues));
                    outcome.put("actual", mask(text, sensitiveValues));
                    outcome.put("status", text.contains(value)
                            ? ExecutionJob.STATUS_PASSED : ExecutionJob.STATUS_ASSERTION_FAILED);
                }
                case "WAIT" -> {
                    waitInterruptibly(Long.parseLong(value), control);
                    outcome.put("actual", "waited");
                    outcome.put("status", ExecutionJob.STATUS_PASSED);
                }
                default -> throw new ExecutionErrorException("不支持的原生 Windows 步骤: " + step.action());
            }
        } catch (ExecutionErrorException e) {
            outcome.put("status", ExecutionJob.STATUS_ERROR);
            outcome.put("error", mask(e.getMessage(), sensitiveValues));
        } catch (NumberFormatException e) {
            outcome.put("status", ExecutionJob.STATUS_ERROR);
            outcome.put("error", "等待时长配置无效");
        }
        return outcome;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findElement(String sessionId, String selector) {
        if (selector == null || selector.isBlank()) {
            throw new ExecutionErrorException("步骤必须提供 selector");
        }
        JsonNode response = request("POST", "/session/" + sessionId + "/element",
                Map.of("using", "accessibility id", "value", selector), sessionId);
        if (!response.isObject() || response.path(ELEMENT_KEY).isMissingNode()) {
            throw new ExecutionErrorException("未找到 UI 元素: " + selector);
        }
        return objectMapper.convertValue(response, Map.class);
    }

    private void execute(String sessionId, String script, List<Object> args) {
        request("POST", "/session/" + sessionId + "/execute/sync",
                Map.of("script", script, "args", args), sessionId);
    }

    private String driverVersion() {
        if (appiumHome == null) {
            return "unknown";
        }
        Path manifest = appiumHome.resolve("node_modules/appium-wincore-driver/package.json");
        try {
            if (!Files.isRegularFile(manifest)) {
                return "unknown";
            }
            return readJson(Files.readString(manifest)).path("version").asText("unknown");
        } catch (IOException | RuntimeException e) {
            return "unknown";
        }
    }

    private JsonNode request(String method, String path, Map<String, Object> body, String sessionId) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(appiumBaseUrl + path))
                    .timeout(Duration.ofSeconds(15));
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = objectMapper.readTree(response.body());
            JsonNode value = parsed.path("value");
            if (response.statusCode() >= 400) {
                throw new ExecutionErrorException("Appium 调用失败: " + value.path("message").asText(response.body()));
            }
            return value;
        } catch (IOException e) {
            throw new ExecutionErrorException("Appium 连接失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionErrorException("Appium 调用被中断", e);
        }
    }

    private void captureScreenshot(String sessionId, Path runDirectory,
                                   List<Map<String, Object>> evidence, String filename) {
        try {
            JsonNode response = request("GET", "/session/" + sessionId + "/screenshot", null, sessionId);
            byte[] bytes = Base64.getDecoder().decode(response.asText());
            Path screenshot = runDirectory.resolve(filename);
            Files.write(screenshot, bytes);
            evidence.add(evidenceEntry("SCREENSHOT", screenshot, "image/png", filename));
        } catch (Exception ignored) {
        }
    }

    private Map<String, Object> evidenceEntry(String kind, Path file, String contentType,
                                               String filename) throws IOException {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", UUID.randomUUID().toString());
        entry.put("kind", kind);
        entry.put("path", evidenceStorage.keyOf(file));
        entry.put("filename", filename);
        entry.put("contentType", contentType);
        entry.put("size", Files.size(file));
        return entry;
    }

    private void deleteSession(String sessionId) {
        try {
            request("DELETE", "/session/" + sessionId, null, sessionId);
        } catch (RuntimeException ignored) {
        }
    }

    private void killAbandonedDemoProcesses() {
        ProcessHandle.allProcesses().forEach(process -> {
            String command = process.info().commandLine().orElse("").toLowerCase();
            if (command.contains("nativedemo")) {
                process.destroyForcibly();
            }
        });
    }

    private void deleteUserData(Path path) {
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private void checkControl(ExecutionControl control) {
        if (control != null && control.cancelled()) {
            throw new ControlException(ExecutionJob.STATUS_CANCELLED, "执行已取消");
        }
        if (control != null && control.deadline() != null && !Instant.now().isBefore(control.deadline())) {
            throw new ControlException(ExecutionJob.STATUS_TIMED_OUT, "执行超时");
        }
    }

    private void waitInterruptibly(long millis, ExecutionControl control) {
        long remaining = Math.min(millis, 60_000);
        while (remaining > 0) {
            checkControl(control);
            long slice = Math.min(remaining, 50);
            try {
                Thread.sleep(slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExecutionErrorException("步骤被中断");
            }
            remaining -= slice;
        }
        checkControl(control);
    }

    private List<StepSpec> parseSteps(JsonNode node) {
        List<StepSpec> steps = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                steps.add(new StepSpec(item.path("action").asText("").toUpperCase(),
                        item.path("selector").isMissingNode() ? null : item.path("selector").asText(""),
                        item.path("value").isMissingNode() ? null : item.path("value").asText("")));
            }
        }
        if (steps.isEmpty()) {
            throw new ExecutionErrorException("原生 Windows 执行配置缺少步骤");
        }
        return steps;
    }

    private String substitute(String template, Map<String, String> variables) {
        if (template == null || !template.contains("{{")) {
            return template;
        }
        String result = template;
        var matcher = VARIABLE.matcher(template);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!variables.containsKey(key)) {
                throw new ExecutionErrorException("变量未定义: " + key);
            }
            result = result.replace("{{" + key + "}}", variables.get(key));
        }
        return result;
    }

    private String mask(String value, List<String> sensitiveValues) {
        if (value == null) {
            return null;
        }
        String result = value;
        List<String> ordered = sensitiveValues.stream().filter(secret -> secret != null && !secret.isEmpty())
                .sorted((a, b) -> Integer.compare(b.length(), a.length())).toList();
        for (String secret : ordered) {
            result = result.replace(secret, MASK);
        }
        return result;
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new ExecutionErrorException("执行配置无法解析", e);
        }
    }

    private Map<String, String> readStringMap(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (Exception e) {
            throw new ExecutionErrorException("环境数据反序列化失败", e);
        }
    }

    private long elapsed(Instant started) {
        return Duration.between(started, Instant.now()).toMillis();
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
