package io.github.calendula597.testplatform.platformserver.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.FrameLocator;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Tracing;
import io.github.calendula597.testplatform.platformserver.environment.Environment;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentMapper;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentVersion;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentVersionMapper;
import io.github.calendula597.testplatform.platformserver.environment.SecretCryptoService;
import io.github.calendula597.testplatform.platformserver.environment.TargetAllowlist;
import io.github.calendula597.testplatform.platformserver.reporting.EvidenceStorage;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersion;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersionMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Component
public class WebExecutor {

    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(10);
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Za-z0-9_.-]+)}}");
    private static final String MASK = "******";

    private final TestCaseVersionMapper versionMapper;
    private final EnvironmentMapper environmentMapper;
    private final EnvironmentVersionMapper environmentVersionMapper;
    private final SecretCryptoService cryptoService;
    private final TargetAllowlist targetAllowlist;
    private final ObjectMapper objectMapper;
    private final EvidenceStorage evidenceStorage;
    private final Path chromiumPath;

    public WebExecutor(TestCaseVersionMapper versionMapper, EnvironmentMapper environmentMapper,
                       EnvironmentVersionMapper environmentVersionMapper, SecretCryptoService cryptoService,
                       TargetAllowlist targetAllowlist, ObjectMapper objectMapper,
                       EvidenceStorage evidenceStorage,
                       @Value("${app.worker.chromium-path:}") String chromiumPath) {
        this.versionMapper = versionMapper;
        this.environmentMapper = environmentMapper;
        this.environmentVersionMapper = environmentVersionMapper;
        this.cryptoService = cryptoService;
        this.targetAllowlist = targetAllowlist;
        this.objectMapper = objectMapper;
        this.evidenceStorage = evidenceStorage;
        this.chromiumPath = chromiumPath == null || chromiumPath.isBlank()
                ? null : Path.of(chromiumPath);
    }

    public record WebOutcome(String status, String reason, List<Map<String, Object>> steps,
                             List<Map<String, Object>> evidence, long durationMs) {
    }

    private record StepSpec(String action, String selector, String value) {
    }

    private static final class RunControlException extends RuntimeException {
        private final String status;

        private RunControlException(String status, String message) {
            super(message);
            this.status = status;
        }
    }

    public WebOutcome execute(long caseVersionId, long environmentId, ExecutionControl control) {
        Instant start = Instant.now();
        List<Map<String, Object>> steps = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        try {
            return doExecute(caseVersionId, environmentId, control, steps, evidence, start);
        } catch (ExecutionErrorException e) {
            return new WebOutcome(ExecutionJob.STATUS_ERROR, e.getMessage(), steps, evidence, elapsed(start));
        } catch (Exception e) {
            return new WebOutcome(ExecutionJob.STATUS_ERROR, "执行器异常: " + e.getMessage(),
                    steps, evidence, elapsed(start));
        }
    }

    private WebOutcome doExecute(long caseVersionId, long environmentId, ExecutionControl control,
                                 List<Map<String, Object>> steps, List<Map<String, Object>> evidence,
                                 Instant start) throws IOException {
        TestCaseVersion version = versionMapper.selectById(caseVersionId);
        if (version == null || !TestCaseVersion.STATUS_READY.equals(version.getStatus())
                || !"WEB".equals(version.getCaseType()) || version.getExecutionJson() == null) {
            throw new ExecutionErrorException("用例版本不可执行：必须是已发布且携带执行配置的 Web 用例");
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
        List<String> sensitiveValues = new ArrayList<>();
        Map<String, String> ciphertexts = readStringMap(envVersion.getSecrets());
        if (!ciphertexts.isEmpty() && !cryptoService.isConfigured()) {
            throw new ExecutionErrorException("密钥加密未配置主密钥，无法解析密钥");
        }
        for (Map.Entry<String, String> entry : ciphertexts.entrySet()) {
            String plain = cryptoService.decrypt(entry.getValue());
            variables.put(entry.getKey(), plain);
            sensitiveValues.add(plain);
        }

        String startUrl = substitute(spec.path("startUrl").asText(""), variables);
        List<StepSpec> stepSpecs = parseSteps(spec.path("steps"));
        Path runDirectory = evidenceStorage.createRunDirectory();
        String runStatus = ExecutionJob.STATUS_PASSED;
        String runReason = null;
        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        Page page = null;
        AtomicReference<String> deniedNavigation = new AtomicReference<>();
        try {
            checkTarget(targets, startUrl);
            playwright = Playwright.create(new Playwright.CreateOptions()
                    .setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setTimeout(actionTimeout(control));
            if (chromiumPath != null) {
                launchOptions.setExecutablePath(chromiumPath);
            }
            browser = playwright.chromium().launch(launchOptions);
            context = browser.newContext();
            context.tracing().start(new Tracing.StartOptions().setScreenshots(true).setSnapshots(true));
            page = context.newPage();
            configureAllowlist(context, targets, deniedNavigation);
            navigate(page, startUrl, targets, deniedNavigation, control);

            FrameLocator frame = null;
            for (int index = 0; index < stepSpecs.size(); index++) {
                checkControl(control);
                StepSpec step = stepSpecs.get(index);
                long stepStart = System.nanoTime();
                Map<String, Object> outcome;
                if ("FRAME".equals(step.action()) || "MAIN_FRAME".equals(step.action())) {
                    outcome = frameStep(step);
                    if ("FRAME".equals(step.action())) {
                        frame = page.frameLocator(step.selector());
                    } else {
                        frame = null;
                    }
                } else {
                    outcome = runStep(step, page, frame, variables, targets, deniedNavigation,
                            runDirectory, index, control, sensitiveValues);
                }
                outcome.put("index", index);
                outcome.put("durationMs", (System.nanoTime() - stepStart) / 1_000_000);
                steps.add(outcome);

                String stepStatus = (String) outcome.get("status");
                if (ExecutionJob.STATUS_ERROR.equals(stepStatus)) {
                    runStatus = ExecutionJob.STATUS_ERROR;
                    runReason = (String) outcome.get("error");
                    break;
                }
                if (ExecutionJob.STATUS_ASSERTION_FAILED.equals(stepStatus)) {
                    runStatus = ExecutionJob.STATUS_ASSERTION_FAILED;
                    runReason = "业务断言未满足";
                    break;
                }
            }
        } catch (RunControlException e) {
            runStatus = e.status;
            runReason = e.getMessage();
        } catch (ExecutionErrorException e) {
            runStatus = ExecutionJob.STATUS_ERROR;
            runReason = e.getMessage();
        } catch (PlaywrightException e) {
            runStatus = ExecutionJob.STATUS_ERROR;
            runReason = deniedNavigation.get() == null
                    ? "Chromium 执行失败: " + e.getMessage()
                    : "目标不在白名单: " + deniedNavigation.get();
        } finally {
            if (page != null) {
                captureRunScreenshot(page, runDirectory, evidence);
            }
            if (context != null) {
                stopTracing(context, runDirectory, evidence);
                closeContext(context);
            }
            if (browser != null) {
                closeBrowser(browser);
            }
            if (playwright != null) {
                closePlaywright(playwright);
            }
            try (var uploads = Files.newDirectoryStream(runDirectory, "upload-*.txt")) {
                for (Path upload : uploads) {
                    Files.deleteIfExists(upload);
                }
            } catch (IOException ignored) {
            }
        }
        return new WebOutcome(runStatus, mask(runReason, sensitiveValues), steps, evidence, elapsed(start));
    }

    private void configureAllowlist(BrowserContext context, List<String> targets,
                                    AtomicReference<String> deniedNavigation) {
        context.onRequest(request -> {
            String url = request.url();
            if (url.startsWith("data:") || url.startsWith("blob:")) {
                return;
            }
            try {
                if (!targetAllowlist.check(targets, url).allowed()) {
                    deniedNavigation.compareAndSet(null, url);
                }
            } catch (RuntimeException e) {
                deniedNavigation.compareAndSet(null, url);
            }
        });
        context.route("**/*", route -> {
            String url = route.request().url();
            if (url.startsWith("data:") || url.startsWith("blob:")) {
                route.resume();
                return;
            }
            try {
                TargetAllowlist.Check check = targetAllowlist.check(targets, url);
                if (check.allowed()) {
                    route.resume();
                } else {
                    deniedNavigation.compareAndSet(null, url);
                    route.abort();
                }
            } catch (RuntimeException e) {
                deniedNavigation.compareAndSet(null, url);
                route.abort();
            }
        });
    }

    private void navigate(Page page, String url, List<String> targets,
                          AtomicReference<String> deniedNavigation, ExecutionControl control) {
        checkTarget(targets, url);
        deniedNavigation.set(null);
        page.setDefaultTimeout(actionTimeout(control));
        page.setDefaultNavigationTimeout(actionTimeout(control));
        try {
            page.navigate(url);
        } catch (PlaywrightException e) {
            checkControl(control);
            if (deniedNavigation.get() != null) {
                throw new ExecutionErrorException("目标不在白名单: " + deniedNavigation.get());
            }
            throw new ExecutionErrorException("导航失败: " + e.getMessage());
        }
        if (deniedNavigation.get() != null) {
            throw new ExecutionErrorException("目标不在白名单: " + deniedNavigation.get());
        }
    }

    private Map<String, Object> frameStep(StepSpec step) {
        Map<String, Object> outcome = baseOutcome(step);
        if ("FRAME".equals(step.action()) && (step.selector() == null || step.selector().isBlank())) {
            outcome.put("status", ExecutionJob.STATUS_ERROR);
            outcome.put("error", "iframe 步骤必须提供 selector");
            return outcome;
        }
        outcome.put("status", ExecutionJob.STATUS_PASSED);
        outcome.put("actual", "FRAME".equals(step.action()) ? "已进入 iframe" : "已返回主框架");
        return outcome;
    }

    private Map<String, Object> runStep(StepSpec step, Page page, FrameLocator frame,
                                        Map<String, String> variables, List<String> targets,
                                        AtomicReference<String> deniedNavigation, Path runDirectory,
                                        int index, ExecutionControl control, List<String> sensitiveValues) {
        Map<String, Object> outcome = baseOutcome(step);
        List<Map<String, Object>> stepEvidence = new ArrayList<>();
        outcome.put("evidence", stepEvidence);
        try {
            checkControl(control);
            double timeout = actionTimeout(control);
            page.setDefaultTimeout(timeout);
            page.setDefaultNavigationTimeout(timeout);
            String value = step.value() == null ? null : substitute(step.value(), variables);
            String actual;
            switch (step.action()) {
                case "NAVIGATE" -> {
                    navigate(page, value, targets, deniedNavigation, control);
                    actual = page.url();
                }
                case "FILL" -> {
                    requireSelector(step);
                    locatorOf(page, frame, step.selector()).fill(value,
                            new Locator.FillOptions().setTimeout(timeout));
                    actual = value;
                }
                case "CLICK" -> {
                    requireSelector(step);
                    locatorOf(page, frame, step.selector()).click(
                            new Locator.ClickOptions().setTimeout(timeout));
                    actual = "clicked";
                }
                case "ASSERT_TEXT" -> {
                    requireSelector(step);
                    String text = locatorOf(page, frame, step.selector()).textContent(
                            new Locator.TextContentOptions().setTimeout(timeout));
                    boolean passed = text != null && text.contains(value == null ? "" : value);
                    outcome.put("expected", mask(value, sensitiveValues));
                    outcome.put("actual", mask(text == null ? "" : text.strip(), sensitiveValues));
                    if (!passed) {
                        outcome.put("status", ExecutionJob.STATUS_ASSERTION_FAILED);
                        captureStepScreenshot(page, runDirectory, stepEvidence, index);
                    }
                    actual = text;
                }
                case "ASSERT_VISIBLE" -> {
                    requireSelector(step);
                    boolean visible = locatorOf(page, frame, step.selector()).isVisible(
                            new Locator.IsVisibleOptions().setTimeout(timeout));
                    outcome.put("expected", "可见");
                    outcome.put("actual", visible ? "可见" : "不可见");
                    if (!visible) {
                        outcome.put("status", ExecutionJob.STATUS_ASSERTION_FAILED);
                        captureStepScreenshot(page, runDirectory, stepEvidence, index);
                    }
                    actual = visible ? "visible" : "invisible";
                }
                case "ASSERT_TITLE" -> {
                    String title = page.title();
                    boolean passed = title.contains(value == null ? "" : value);
                    outcome.put("expected", mask(value, sensitiveValues));
                    outcome.put("actual", mask(title, sensitiveValues));
                    if (!passed) {
                        outcome.put("status", ExecutionJob.STATUS_ASSERTION_FAILED);
                        captureStepScreenshot(page, runDirectory, stepEvidence, index);
                    }
                    actual = title;
                }
                case "UPLOAD" -> {
                    requireSelector(step);
                    Path upload = runDirectory.resolve("upload-" + UUID.randomUUID() + ".txt");
                    Files.writeString(upload, value == null ? "" : value, StandardCharsets.UTF_8);
                    locatorOf(page, frame, step.selector()).setInputFiles(upload,
                            new Locator.SetInputFilesOptions().setTimeout(timeout));
                    actual = "uploaded";
                }
                case "DOWNLOAD" -> {
                    requireSelector(step);
                    Download download = page.waitForDownload(() -> locatorOf(page, frame, step.selector())
                            .click(new Locator.ClickOptions().setTimeout(timeout)));
                    String filename = safeFilename(download.suggestedFilename());
                    Path saved = runDirectory.resolve("download-" + UUID.randomUUID() + "-" + filename);
                    download.saveAs(saved);
                    stepEvidence.add(evidenceEntry("DOWNLOAD", saved, "application/octet-stream", filename));
                    outcome.put("filename", filename);
                    actual = filename;
                }
                case "WAIT" -> {
                    long millis = value == null ? 0 : Math.min(Long.parseLong(value), 10_000);
                    waitInterruptibly(millis, control);
                    actual = "waited";
                }
                default -> throw new ExecutionErrorException("不支持的步骤类型: " + step.action());
            }
            outcome.put("actual", mask(actual, sensitiveValues));
            if (!outcome.containsKey("status")) {
                outcome.put("status", ExecutionJob.STATUS_PASSED);
            }
        } catch (ExecutionErrorException e) {
            outcome.put("status", ExecutionJob.STATUS_ERROR);
            outcome.put("error", mask(e.getMessage(), sensitiveValues));
            captureStepScreenshot(page, runDirectory, stepEvidence, index);
        } catch (PlaywrightException e) {
            checkControl(control);
            outcome.put("status", ExecutionJob.STATUS_ERROR);
            String message = deniedNavigation.get() == null ? "定位或浏览器操作失败: " + e.getMessage()
                    : "目标不在白名单: " + deniedNavigation.get();
            outcome.put("error", mask(message, sensitiveValues));
            captureStepScreenshot(page, runDirectory, stepEvidence, index);
        } catch (IOException | NumberFormatException e) {
            outcome.put("status", ExecutionJob.STATUS_ERROR);
            outcome.put("error", mask("步骤配置或文件操作失败: " + e.getMessage(), sensitiveValues));
            captureStepScreenshot(page, runDirectory, stepEvidence, index);
        }
        return outcome;
    }

    private Map<String, Object> baseOutcome(StepSpec step) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("action", step.action());
        if (step.selector() != null) {
            outcome.put("selector", step.selector());
        }
        outcome.put("evidence", new ArrayList<Map<String, Object>>());
        return outcome;
    }

    private void waitInterruptibly(long millis, ExecutionControl control) {
        long remaining = millis;
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

    private void checkControl(ExecutionControl control) {
        if (control != null && control.cancelled()) {
            throw new RunControlException(ExecutionJob.STATUS_CANCELLED, "执行已取消");
        }
        if (control != null && control.deadline() != null
                && !Instant.now().isBefore(control.deadline())) {
            throw new RunControlException(ExecutionJob.STATUS_TIMED_OUT, "执行超时");
        }
    }

    private double actionTimeout(ExecutionControl control) {
        long timeout = ACTION_TIMEOUT.toMillis();
        if (control != null && control.deadline() != null) {
            timeout = Math.min(timeout, Math.max(1, Duration.between(Instant.now(), control.deadline()).toMillis()));
        }
        return timeout;
    }

    private void checkTarget(List<String> targets, String url) {
        try {
            TargetAllowlist.Check check = targetAllowlist.check(targets, url);
            if (!check.allowed()) {
                throw new ExecutionErrorException("目标不在白名单: " + url);
            }
        } catch (IllegalArgumentException e) {
            throw new ExecutionErrorException("目标地址无效: " + url, e);
        }
    }

    private Locator locatorOf(Page page, FrameLocator frame, String selector) {
        return frame == null ? page.locator(selector) : frame.locator(selector);
    }

    private void requireSelector(StepSpec step) {
        if (step.selector() == null || step.selector().isBlank()) {
            throw new ExecutionErrorException(step.action() + " 步骤必须提供 selector");
        }
    }

    private String safeFilename(String suggested) {
        String sanitized = suggested == null ? "download.bin"
                : suggested.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() || sanitized.equals(".") || sanitized.equals("..")
                ? "download.bin" : sanitized;
    }

    private void captureRunScreenshot(Page page, Path runDirectory, List<Map<String, Object>> evidence) {
        try {
            Path screenshot = runDirectory.resolve("final.png");
            page.screenshot(new Page.ScreenshotOptions().setPath(screenshot).setTimeout(3_000));
            evidence.add(evidenceEntry("SCREENSHOT", screenshot, "image/png", "final.png"));
        } catch (Exception ignored) {
            // Cleanup must preserve the original browser outcome.
        }
    }

    private void captureStepScreenshot(Page page, Path runDirectory,
                                       List<Map<String, Object>> evidence, int index) {
        try {
            Path screenshot = runDirectory.resolve("step-" + index + "-failure.png");
            page.screenshot(new Page.ScreenshotOptions().setPath(screenshot).setTimeout(3_000));
            evidence.add(evidenceEntry("SCREENSHOT", screenshot, "image/png",
                    "step-" + index + "-failure.png"));
        } catch (Exception ignored) {
            // A closed page must not hide the step failure.
        }
    }

    private void stopTracing(BrowserContext context, Path runDirectory,
                             List<Map<String, Object>> evidence) {
        try {
            Path trace = runDirectory.resolve("trace.zip");
            context.tracing().stop(new Tracing.StopOptions().setPath(trace));
            evidence.add(evidenceEntry("TRACE", trace, "application/zip", "trace.zip"));
        } catch (Exception ignored) {
            // Trace failure must not replace the execution result.
        }
    }

    private void closeContext(BrowserContext context) {
        try {
            context.close();
        } catch (PlaywrightException ignored) {
        }
    }

    private void closeBrowser(Browser browser) {
        try {
            browser.close();
        } catch (PlaywrightException ignored) {
        }
    }

    private void closePlaywright(Playwright playwright) {
        try {
            playwright.close();
        } catch (PlaywrightException ignored) {
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

    private List<StepSpec> parseSteps(JsonNode stepsNode) {
        List<StepSpec> steps = new ArrayList<>();
        if (stepsNode.isArray()) {
            for (JsonNode node : stepsNode) {
                steps.add(new StepSpec(node.path("action").asText("").toUpperCase(),
                        node.path("selector").isMissingNode() ? null : node.path("selector").asText(""),
                        node.path("value").isMissingNode() ? null : node.path("value").asText("")));
            }
        }
        if (steps.isEmpty()) {
            throw new ExecutionErrorException("Web 执行配置缺少步骤");
        }
        return steps;
    }

    private String substitute(String template, Map<String, String> variables) {
        if (template == null || !template.contains("{{")) {
            return template;
        }
        String result = template;
        java.util.regex.Matcher matcher = VARIABLE.matcher(template);
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
        List<String> ordered = sensitiveValues.stream()
                .filter(secret -> secret != null && !secret.isEmpty())
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .toList();
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

    private long elapsed(Instant start) {
        return Duration.between(start, Instant.now()).toMillis();
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
