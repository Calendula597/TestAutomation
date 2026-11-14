package io.github.calendula597.testplatform.platformserver.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Real dependency probes a Worker runs for environment diagnostics. Every
 * probe reports the truth: a missing dependency is an explicit failure
 * with detail, never a simulated success. Probes are read-only and safe
 * to requeue after a lost lease.
 *
 * Probes never spawn subprocesses: executable tools are checked as real
 * PATH entries, services are probed over HTTP, so the control plane keeps
 * zero command-execution surface.
 */
@Component
public class DiagnosticRunner {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticRunner.class);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    public record CheckResult(String name, boolean ok, String detail) {
    }

    public record Outcome(String status, List<CheckResult> checks) {
    }

    private final String modelBaseUrl;
    private final String chromiumPath;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(PROBE_TIMEOUT)
            .build();

    public DiagnosticRunner(@Value("${app.model.base-url:}") String modelBaseUrl,
                            @Value("${app.worker.chromium-path:}") String chromiumPath) {
        this.modelBaseUrl = modelBaseUrl == null ? "" : modelBaseUrl.trim();
        this.chromiumPath = chromiumPath == null ? "" : chromiumPath.trim();
    }

    public Outcome run(List<String> requestedChecks) {
        List<CheckResult> checks = new ArrayList<>();
        boolean allProbesCompleted = true;
        for (String name : requestedChecks) {
            try {
                checks.add(probe(name));
            } catch (Exception e) {
                log.warn("诊断探针 {} 执行异常", name, e);
                checks.add(new CheckResult(name, false, "探针执行异常: " + e.getMessage()));
                allProbesCompleted = false;
        }
        }
        return new Outcome(allProbesCompleted ? WorkerDiagnostic.STATUS_PASSED : WorkerDiagnostic.STATUS_ERROR,
                checks);
    }

    /** The payload only selects from hard-coded probes; unknown names are rejected. */
    private CheckResult probe(String name) {
        return switch (name) {
            case "jvm" -> probeJvm();
            case "model-provider" -> probeModelProvider();
            case "chromium" -> probeChromium();
            case "appium" -> probeAppium();
            default -> new CheckResult(name, false, "未知的诊断项: " + name);
        };
    }

    private CheckResult probeJvm() {
        String version = System.getProperty("java.version", "unknown");
        return new CheckResult("jvm", true, "Java " + version);
    }

    /** The model provider must be configured or reported missing — no silence. */
    private CheckResult probeModelProvider() {
        if (modelBaseUrl.isEmpty()) {
            return new CheckResult("model-provider", false, "未配置 APP_MODEL_BASE_URL");
        }
        try {
            HttpResponse<Void> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(modelBaseUrl))
                            .timeout(PROBE_TIMEOUT)
                            .method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            return new CheckResult("model-provider", true,
                    "HTTP " + response.statusCode() + " @ " + modelBaseUrl);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new CheckResult("model-provider", false, "无法连接 " + modelBaseUrl + ": " + e.getMessage());
        }
    }

    private CheckResult probeChromium() {
        if (!chromiumPath.isEmpty()) {
            boolean exists = Files.exists(Path.of(chromiumPath));
            return new CheckResult("chromium", exists, exists ? "已配置: " + chromiumPath
                    : "配置的路径不存在: " + chromiumPath);
        }
        for (Path candidate : chromiumCandidates()) {
            if (Files.exists(candidate)) {
                return new CheckResult("chromium", true, "发现浏览器: " + candidate);
            }
        }
        return new CheckResult("chromium", false, "未找到 Chromium/Chrome 可执行文件");
    }

    private List<Path> chromiumCandidates() {
        String os = System.getProperty("os.name", "").toLowerCase();
        List<Path> candidates = new ArrayList<>();
        if (os.contains("win")) {
            String programFiles = System.getenv("ProgramFiles");
            String programFilesX86 = System.getenv("ProgramFiles(x86)");
            String localAppData = System.getenv("LOCALAPPDATA");
            if (programFiles != null) {
                candidates.add(Path.of(programFiles, "Google", "Chrome", "Application", "chrome.exe"));
            }
            if (programFilesX86 != null) {
                candidates.add(Path.of(programFilesX86, "Google", "Chrome", "Application", "chrome.exe"));
            }
            if (localAppData != null) {
                candidates.add(Path.of(localAppData, "Google", "Chrome", "Application", "chrome.exe"));
            }
        } else if (os.contains("mac")) {
            candidates.add(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
            candidates.add(Path.of("/Applications/Chromium.app/Contents/MacOS/Chromium"));
        } else {
            candidates.add(Path.of("/usr/bin/google-chrome"));
            candidates.add(Path.of("/usr/bin/chromium"));
            candidates.add(Path.of("/usr/bin/chromium-browser"));
        }
        return candidates;
    }

    /** Real PATH scan for the tool executable; absence is explicit. */
    private CheckResult probeAppium() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isBlank()) {
            return new CheckResult("appium", false, "无法读取 PATH 环境变量");
        }
        List<String> names = isWindows() ? List.of("appium.cmd", "appium.bat", "appium.exe", "appium")
                : List.of("appium");
        for (String dir : pathEnv.split(File.pathSeparator)) {
            for (String name : names) {
                Path candidate = Path.of(dir, name);
                if (Files.isRegularFile(candidate)) {
                    return new CheckResult("appium", true, "在 PATH 中发现: " + candidate);
                }
            }
        }
        return new CheckResult("appium", false, "未在 PATH 中发现 appium 可执行文件");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
