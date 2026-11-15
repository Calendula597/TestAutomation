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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class AndroidExecutor {

    private static final String PACKAGE = "io.github.calendula597.demo";
    private static final String ELEMENT_KEY = "element-6066-11e4-a52e-4f735466cecf";
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Za-z0-9_.-]+)}}");

    private final TestCaseVersionMapper versionMapper;
    private final EnvironmentMapper environmentMapper;
    private final EnvironmentVersionMapper environmentVersionMapper;
    private final SecretCryptoService cryptoService;
    private final ObjectMapper objectMapper;
    private final EvidenceStorage evidenceStorage;
    private final JdbcTemplate jdbc;
    private final String appiumBaseUrl;
    private final Path appiumHome;
    private final Path adb;
    private final Path demoApk;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public AndroidExecutor(TestCaseVersionMapper versionMapper, EnvironmentMapper environmentMapper,
                           EnvironmentVersionMapper environmentVersionMapper, SecretCryptoService cryptoService,
                           ObjectMapper objectMapper, EvidenceStorage evidenceStorage, JdbcTemplate jdbc,
                           @Value("${app.worker.appium-base-url:http://127.0.0.1:4723}") String appiumBaseUrl,
                           @Value("${app.worker.appium-home:}") String appiumHome,
                           @Value("${app.worker.android-sdk:}") String androidSdk,
                           @Value("${app.worker.android-demo-apk:}") String demoApk) {
        this.versionMapper = versionMapper;
        this.environmentMapper = environmentMapper;
        this.environmentVersionMapper = environmentVersionMapper;
        this.cryptoService = cryptoService;
        this.objectMapper = objectMapper;
        this.evidenceStorage = evidenceStorage;
        this.jdbc = jdbc;
        this.appiumBaseUrl = appiumBaseUrl.replaceAll("/$", "");
        this.appiumHome = appiumHome.isBlank() ? null : Path.of(appiumHome);
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "adb.exe" : "adb";
        this.adb = androidSdk.isBlank() ? null : Path.of(androidSdk, "platform-tools", executable);
        this.demoApk = demoApk.isBlank() ? null : Path.of(demoApk);
    }

    public record AndroidOutcome(String status, String reason, List<Map<String, Object>> steps,
                                 List<Map<String, Object>> evidence, long durationMs,
                                 String appType, String driverName, String driverVersion,
                                 String deviceSerial, String deviceModel, String platformVersion) {
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

    @Transactional
    public AndroidOutcome execute(long caseVersionId, long environmentId, ExecutionControl control) {
        Instant started = Instant.now();
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        List<String> sensitiveValues = new ArrayList<>();
        String sessionId = null;
        Path runDirectory = null;
        String serial = "unknown";
        String deviceModel = "unknown";
        String platformVersion = "unknown";
        String status = ExecutionJob.STATUS_PASSED;
        String reason = null;
        try {
            TestCaseVersion version = versionMapper.selectById(caseVersionId);
            if (version == null || !TestCaseVersion.STATUS_READY.equals(version.getStatus())
                    || !"ANDROID".equals(version.getCaseType()) || version.getExecutionJson() == null) {
                throw new ExecutionErrorException("用例版本不可执行：必须是已发布的 Android 用例");
            }
            JsonNode spec = readJson(version.getExecutionJson());
            if (!"demo-android".equals(spec.path("app").asText())
                    || !spec.path("reset").asBoolean(false)
                    || !spec.path("deviceSerial").asText().matches("[A-Za-z0-9._:-]{1,100}")) {
                throw new ExecutionErrorException("Android 执行必须选择演示应用、显式重置和设备序列号");
            }
            if (adb == null || !Files.isRegularFile(adb) || demoApk == null || !Files.isRegularFile(demoApk)) {
                throw new ExecutionErrorException("Android SDK 或演示 APK 未配置");
            }
            serial = spec.path("deviceSerial").asText();
            if (!Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT pg_try_advisory_xact_lock(hashtext(?))", Boolean.class, serial))) {
                throw new ExecutionErrorException("Android 设备正被其他执行占用: " + serial);
            }
            checkConnected(serial);
            deviceModel = deviceProperty(serial, "ro.product.model");
            platformVersion = deviceProperty(serial, "ro.build.version.release");
            Map<String, String> variables = environmentVariables(environmentId, sensitiveValues);
            runDirectory = evidenceStorage.createRunDirectory();
            uninstallDemoApplication(serial);
            sessionId = createSession(serial);
            resetApplication(serial);
            List<StepSpec> stepSpecs = parseSteps(spec.path("steps"));
            for (int index = 0; index < stepSpecs.size(); index++) {
                checkControl(control);
                StepSpec step = stepSpecs.get(index);
                long stepStarted = System.nanoTime();
                Map<String, Object> outcome = runStep(sessionId, serial, step, variables, sensitiveValues, control);
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
                    reason = "Android 业务断言未满足";
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
            reason = "Android 执行器异常: " + e.getMessage();
        } finally {
            if (sessionId != null) {
                captureScreenshot(sessionId, runDirectory, evidence, "final.png");
                deleteSession(sessionId);
            }
            if (!"unknown".equals(serial)) {
                try {
                    adb(serial, "shell", "am", "force-stop", PACKAGE);
                    adb(serial, "shell", "pm", "clear", PACKAGE);
                } catch (RuntimeException ignored) {
                }
            }
        }
        return new AndroidOutcome(status, mask(reason, sensitiveValues), steps, evidence,
                Duration.between(started, Instant.now()).toMillis(), "ANDROID_APK", "UiAutomator2",
                driverVersion(), serial, deviceModel, platformVersion);
    }

    private Map<String, String> environmentVariables(long environmentId, List<String> sensitiveValues) {
        Environment environment = environmentMapper.selectById(environmentId);
        if (environment == null) {
            throw new ExecutionErrorException("环境不存在");
        }
        EnvironmentVersion version = environmentVersionMapper.selectOne(new LambdaQueryWrapper<EnvironmentVersion>()
                .eq(EnvironmentVersion::getEnvironmentId, environmentId)
                .eq(EnvironmentVersion::getVersion, environment.getCurrentVersion()));
        if (version == null) {
            throw new ExecutionErrorException("环境版本数据缺失");
        }
        Map<String, String> variables = readStringMap(version.getPlainVariables());
        Map<String, String> secrets = readStringMap(version.getSecrets());
        if (!secrets.isEmpty() && !cryptoService.isConfigured()) {
            throw new ExecutionErrorException("密钥加密未配置主密钥");
        }
        for (Map.Entry<String, String> entry : secrets.entrySet()) {
            String plain = cryptoService.decrypt(entry.getValue());
            variables.put(entry.getKey(), plain);
            sensitiveValues.add(plain);
        }
        return variables;
    }

    private String createSession(String serial) {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("platformName", "Android");
        capabilities.put("appium:automationName", "UiAutomator2");
        capabilities.put("appium:udid", serial);
        capabilities.put("appium:deviceName", serial);
        capabilities.put("appium:app", demoApk.toAbsolutePath().normalize().toString());
        capabilities.put("appium:appPackage", PACKAGE);
        capabilities.put("appium:appActivity", PACKAGE + ".MainActivity");
        capabilities.put("appium:noReset", false);
        capabilities.put("appium:fullReset", true);
        capabilities.put("appium:enforceAppInstall", true);
        capabilities.put("appium:autoGrantPermissions", true);
        capabilities.put("appium:shouldTerminateApp", true);
        capabilities.put("appium:newCommandTimeout", 30);
        capabilities.put("appium:adbExecTimeout", 30_000);
        capabilities.put("appium:uiautomator2ServerLaunchTimeout", 60_000);
        capabilities.put("appium:disableWindowAnimation", true);
        JsonNode response;
        try {
            response = request("POST", "/session", Map.of("capabilities",
                    Map.of("alwaysMatch", capabilities)));
        } catch (ExecutionErrorException e) {
            try {
                checkConnected(serial);
            } catch (ExecutionErrorException disconnected) {
                throw new ExecutionErrorException("Android 设备已断连: " + disconnected.getMessage(), disconnected);
            }
            throw e;
        }
        String sessionId = response.path("sessionId").asText("");
        if (sessionId.isBlank()) {
            throw new ExecutionErrorException("Android UiAutomator2 未返回会话 ID");
        }
        return sessionId;
    }

    private void uninstallDemoApplication(String serial) {
        String installed = adb(serial, "shell", "pm", "list", "packages", PACKAGE);
        if (!installed.contains("package:" + PACKAGE)) {
            return;
        }
        String outcome = adb(serial, "shell", "pm", "uninstall", PACKAGE);
        if (!outcome.contains("Success")
                && adb(serial, "shell", "pm", "list", "packages", PACKAGE).contains(PACKAGE)) {
            throw new ExecutionErrorException("无法卸载旧版 Android 演示应用: " + outcome);
        }
    }

    private void resetApplication(String serial) {
        checkConnected(serial);
        adb(serial, "shell", "pm", "clear", PACKAGE);
        adb(serial, "shell", "am", "force-stop", PACKAGE);
        adb(serial, "shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity");
    }

    private Map<String, Object> runStep(String sessionId, String serial, StepSpec step,
                                        Map<String, String> variables, List<String> sensitiveValues,
                                        ExecutionControl control) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("action", step.action());
        if (step.selector() != null) {
            outcome.put("selector", step.selector());
        }
        try {
            checkConnected(serial);
            String value = substitute(step.value(), variables);
            switch (step.action()) {
                case "FILL" -> {
                    Map<String, Object> element = findElement(sessionId, step.selector());
                    String elementId = String.valueOf(element.get(ELEMENT_KEY));
                    request("POST", "/session/" + sessionId + "/element/" + elementId + "/value",
                            Map.of("text", value));
                    outcome.put("actual", mask(value, sensitiveValues));
                    outcome.put("status", ExecutionJob.STATUS_PASSED);
                }
                case "CLICK" -> {
                    Map<String, Object> element = findElement(sessionId, step.selector());
                    String elementId = String.valueOf(element.get(ELEMENT_KEY));
                    request("POST", "/session/" + sessionId + "/element/" + elementId + "/click", Map.of());
                    outcome.put("actual", "clicked");
                    outcome.put("status", ExecutionJob.STATUS_PASSED);
                }
                case "ASSERT_TEXT" -> {
                    Map<String, Object> element = findElement(sessionId, step.selector());
                    String elementId = String.valueOf(element.get(ELEMENT_KEY));
                    String text = request("GET", "/session/" + sessionId + "/element/" + elementId + "/text",
                            null).asText();
                    outcome.put("expected", mask(value, sensitiveValues));
                    outcome.put("actual", mask(text, sensitiveValues));
                    outcome.put("status", text.contains(value)
                            ? ExecutionJob.STATUS_PASSED : ExecutionJob.STATUS_ASSERTION_FAILED);
                }
                case "WAIT" -> {
                    waitInterruptibly(serial, Long.parseLong(value), control);
                    outcome.put("actual", "waited");
                    outcome.put("status", ExecutionJob.STATUS_PASSED);
                }
                default -> throw new ExecutionErrorException("不支持的 Android 步骤: " + step.action());
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
                Map.of("using", "accessibility id", "value", selector));
        if (!response.isObject() || response.path(ELEMENT_KEY).isMissingNode()) {
            throw new ExecutionErrorException("未找到 Android UI 元素: " + selector);
        }
        return objectMapper.convertValue(response, Map.class);
    }

    private void checkConnected(String serial) {
        String state = adb(serial, "get-state").trim();
        if (!"device".equals(state)) {
            throw new ExecutionErrorException("Android 设备已断连: " + serial + " (" + state + ")");
        }
    }

    private String deviceProperty(String serial, String property) {
        try {
            String value = adb(serial, "shell", "getprop", property).trim();
            return value.isEmpty() ? "unknown" : value;
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private String adb(String serial, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(adb.toString());
        command.add("-s");
        command.add(serial);
        command.addAll(List.of(arguments));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ExecutionErrorException("Android 设备命令超时: " + serial);
            }
            String output;
            try (var stream = process.getInputStream()) {
                output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (process.exitValue() != 0) {
                throw new ExecutionErrorException("Android 设备已断连或命令失败: " + output.trim());
            }
            return output;
        } catch (IOException e) {
            throw new ExecutionErrorException("Android 设备连接失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionErrorException("Android 设备命令被中断", e);
        }
    }

    private JsonNode request(String method, String path, Map<String, Object> body) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(appiumBaseUrl + path))
                    .timeout(Duration.ofSeconds("/session".equals(path) ? 120 : 25));
            if (body != null) {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            } else {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode value = objectMapper.readTree(response.body()).path("value");
            if (response.statusCode() >= 400) {
                throw new ExecutionErrorException("Android Appium 调用失败: "
                        + value.path("message").asText(response.body()));
            }
            return value;
        } catch (IOException e) {
            throw new ExecutionErrorException("Android 设备或 Appium 连接失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionErrorException("Android Appium 调用被中断", e);
        }
    }

    private String driverVersion() {
        if (appiumHome == null) {
            return "unknown";
        }
        try {
            return readJson(Files.readString(appiumHome.resolve(
                    "node_modules/appium-uiautomator2-driver/package.json"))).path("version").asText("unknown");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void captureScreenshot(String sessionId, Path runDirectory,
                                   List<Map<String, Object>> evidence, String filename) {
        try {
            byte[] bytes = Base64.getDecoder().decode(
                    request("GET", "/session/" + sessionId + "/screenshot", null).asText());
            Path screenshot = runDirectory.resolve(filename);
            Files.write(screenshot, bytes);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", UUID.randomUUID().toString());
            entry.put("kind", "SCREENSHOT");
            entry.put("path", evidenceStorage.keyOf(screenshot));
            entry.put("filename", filename);
            entry.put("contentType", "image/png");
            entry.put("size", Files.size(screenshot));
            evidence.add(entry);
        } catch (Exception ignored) {
        }
    }

    private void deleteSession(String sessionId) {
        try {
            request("DELETE", "/session/" + sessionId, null);
        } catch (RuntimeException ignored) {
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

    private void waitInterruptibly(String serial, long millis, ExecutionControl control) {
        long remaining = Math.min(millis, 60_000);
        while (remaining > 0) {
            checkControl(control);
            checkConnected(serial);
            try {
                Thread.sleep(Math.min(remaining, 250));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ExecutionErrorException("Android 步骤被中断");
            }
            remaining -= Math.min(remaining, 250);
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
            throw new ExecutionErrorException("Android 执行配置缺少步骤");
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
        for (String secret : sensitiveValues.stream().filter(secret -> secret != null && !secret.isEmpty())
                .sorted((a, b) -> Integer.compare(b.length(), a.length())).toList()) {
            result = result.replace(secret, "******");
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

    public static class ExecutionErrorException extends RuntimeException {
        public ExecutionErrorException(String message) {
            super(message);
        }

        public ExecutionErrorException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
