package io.github.calendula597.testplatform.platformserver.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.github.calendula597.testplatform.platformserver.environment.Environment;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentMapper;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentVersion;
import io.github.calendula597.testplatform.platformserver.environment.EnvironmentVersionMapper;
import io.github.calendula597.testplatform.platformserver.reporting.EvidenceStorage;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersion;
import io.github.calendula597.testplatform.platformserver.testcase.TestCaseVersionMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class PerformanceExecutor {

    private final TestCaseVersionMapper versionMapper;
    private final EnvironmentMapper environmentMapper;
    private final EnvironmentVersionMapper environmentVersionMapper;
    private final PerformanceGuard guard;
    private final ObjectMapper objectMapper;
    private final EvidenceStorage evidenceStorage;
    private final String k6Path;
    private final String k6Image;

    public PerformanceExecutor(TestCaseVersionMapper versionMapper, EnvironmentMapper environmentMapper,
                               EnvironmentVersionMapper environmentVersionMapper, PerformanceGuard guard,
                               ObjectMapper objectMapper, EvidenceStorage evidenceStorage,
                               @Value("${app.worker.k6-path:}") String k6Path,
                               @Value("${app.worker.k6-image:grafana/k6:2.4.0@sha256:d41bb79cd8de5a7ad619b53570e3712633e8bff0937010a5c6c57a4747e7a276}")
                               String k6Image) {
        this.versionMapper = versionMapper;
        this.environmentMapper = environmentMapper;
        this.environmentVersionMapper = environmentVersionMapper;
        this.guard = guard;
        this.objectMapper = objectMapper;
        this.evidenceStorage = evidenceStorage;
        this.k6Path = k6Path;
        this.k6Image = k6Image;
    }

    public record PerformanceOutcome(String status, String reason, Map<String, Object> metrics,
                                     List<Map<String, Object>> thresholds, List<Map<String, Object>> evidence,
                                     long durationMs, String engine, String engineVersion) {
    }

    private static final class ControlException extends RuntimeException {
        private final String status;

        private ControlException(String status, String message) {
            super(message);
            this.status = status;
        }
    }

    public PerformanceOutcome execute(long caseVersionId, long environmentId, ExecutionControl control) {
        Instant started = Instant.now();
        Path runDirectory = null;
        Process process = null;
        Path containerIdFile = null;
        try {
            TestCaseVersion version = versionMapper.selectById(caseVersionId);
            if (version == null || !TestCaseVersion.STATUS_READY.equals(version.getStatus())
                    || !"PERFORMANCE".equals(version.getCaseType()) || version.getExecutionJson() == null) {
                throw new ExecutionErrorException("用例版本不可执行：必须是已发布的性能用例");
            }
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
            List<String> targets = readStringList(envVersion.getTargets());
            guard.validate(version.getExecutionJson(), targets, variables);
            JsonNode spec = objectMapper.readTree(version.getExecutionJson());

            runDirectory = evidenceStorage.createRunDirectory();
            Path script = runDirectory.resolve("performance.js");
            Path summary = runDirectory.resolve("summary.json");
            Path output = runDirectory.resolve("output.log");
            containerIdFile = runDirectory.resolve("container.id");
            Files.writeString(script, createScript(spec, variables));
            List<String> command = k6Path == null || k6Path.isBlank()
                    ? dockerCommand(runDirectory, script, summary, containerIdFile)
                    : binaryCommand(script, summary);
            process = new ProcessBuilder(command).redirectErrorStream(true)
                    .redirectOutput(output.toFile()).start();
            String status = waitForCompletion(process, control);
            if (!"PASSED".equals(status)) {
                throw new ExecutionErrorException("k6 执行失败: " + outputTail(output));
            }
            if (!Files.isRegularFile(summary)) {
                throw new ExecutionErrorException("k6 未生成 summary-export，状态=" + status
                        + "，输出=" + outputTail(output));
            }
            List<Map<String, Object>> evidence = new ArrayList<>();
            evidence.add(evidenceEntry("K6_SUMMARY", summary, "application/json", "summary.json"));
            evidence.add(evidenceEntry("K6_OUTPUT", output, "text/plain", "output.log"));
            evidence.add(evidenceEntry("K6_SCRIPT", script, "application/javascript", "performance.js"));
            JsonNode summaryJson = objectMapper.readTree(Files.readString(summary));
            Map<String, Object> metrics = metrics(summaryJson);
            List<Map<String, Object>> thresholdResults = thresholdResults(spec.path("thresholds"), metrics);
            boolean passed = thresholdResults.stream().allMatch(result -> Boolean.TRUE.equals(result.get("passed")));
            String outcomeStatus = !"PASSED".equals(status) ? ExecutionJob.STATUS_ERROR
                    : passed ? ExecutionJob.STATUS_PASSED : ExecutionJob.STATUS_ASSERTION_FAILED;
            return new PerformanceOutcome(outcomeStatus,
                    !passed || !"PASSED".equals(status) ? statusReason(status, passed) : null,
                    metrics, thresholdResults, evidence, elapsed(started), "k6", "2.4.0");
        } catch (ControlException e) {
            return new PerformanceOutcome(e.status, e.getMessage(), Map.of(), List.of(), List.of(), elapsed(started),
                    "k6", "2.4.0");
        } catch (ExecutionErrorException e) {
            return new PerformanceOutcome(ExecutionJob.STATUS_ERROR, e.getMessage(), Map.of(), List.of(), List.of(),
                    elapsed(started), "k6", "2.4.0");
        } catch (Exception e) {
            return new PerformanceOutcome(ExecutionJob.STATUS_ERROR, "执行器异常: " + e.getMessage(), Map.of(), List.of(),
                    List.of(), elapsed(started), "k6", "2.4.0");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (containerIdFile != null && Files.isRegularFile(containerIdFile)) {
                try {
                    String containerId = Files.readString(containerIdFile).trim();
                    if (!containerId.isBlank()) {
                        new ProcessBuilder("docker", "kill", containerId).start().waitFor(5, TimeUnit.SECONDS);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private List<String> dockerCommand(Path runDirectory, Path script, Path summary,
                                       Path containerIdFile) throws Exception {
        List<String> args = new ArrayList<>(List.of("docker", "run", "--rm",
                "--add-host", "host.docker.internal:host-gateway"));
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            args.add("--user");
            args.add(hostDockerUser());
            args.add("-e");
            args.add("HOME=/tmp");
        }
        args.addAll(List.of("--cidfile", containerIdFile.toString(), "-v", runDirectory + ":/work", k6Image,
                "run", "/work/" + script.getFileName(), "--summary-export", "/work/" + summary.getFileName()));
        return args;
    }

    private String hostDockerUser() throws Exception {
        return readHostId("-u") + ":" + readHostId("-g");
    }

    private String readHostId(String option) throws Exception {
        Process process = new ProcessBuilder("id", option).redirectErrorStream(true).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("读取宿主机用户映射超时");
        }
        String id = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0 || !id.matches("[0-9]+")) {
            throw new IOException("无法确定 k6 容器用户映射");
        }
        return id;
    }

    private List<String> binaryCommand(Path script, Path summary) {
        return List.of(k6Path, "run", script.toString(), "--summary-export", summary.toString());
    }

    private String waitForCompletion(Process process, ExecutionControl control) throws Exception {
        while (!process.waitFor(50, TimeUnit.MILLISECONDS)) {
            checkControl(control);
        }
        int exit = process.exitValue();
        return exit == 0 ? "PASSED" : "ERROR";
    }

    private void checkControl(ExecutionControl control) {
        if (control != null && control.cancelled()) {
            throw new ControlException(ExecutionJob.STATUS_CANCELLED, "执行已取消");
        }
        if (control != null && control.deadline() != null && !Instant.now().isBefore(control.deadline())) {
            throw new ControlException(ExecutionJob.STATUS_TIMED_OUT, "执行超时");
        }
    }

    private String createScript(JsonNode spec, Map<String, String> variables) throws Exception {
        String target = substitute(spec.path("target").asText(), variables);
        StringBuilder stages = new StringBuilder();
        for (JsonNode stage : spec.path("stages")) {
            if (!stages.isEmpty()) {
                stages.append(",");
            }
            stages.append("{duration:'").append(stage.path("durationSeconds").asInt())
                    .append("s',target:").append(stage.path("target").asInt()).append("}");
        }
        return "import http from 'k6/http';\n"
                + "export const options={stages:[" + stages + "],maxRedirects:0};\n"
                + "export default function(){http.get(" + objectMapper.writeValueAsString(target) + ");}\n";
    }

    private Map<String, Object> metrics(JsonNode summary) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        JsonNode requests = summary.path("metrics").path("http_reqs");
        metrics.put("count", requests.path("count").asLong());
        metrics.put("throughputPerSecond", requests.path("rate").asDouble());
        JsonNode failed = summary.path("metrics").path("http_req_failed");
        metrics.put("errorRate", failed.has("value") ? failed.path("value").asDouble()
                : failed.path("rate").asDouble());
        JsonNode duration = summary.path("metrics").path("http_req_duration");
        metrics.put("p90Ms", duration.path("p(90)").asDouble());
        metrics.put("p95Ms", duration.path("p(95)").asDouble());
        metrics.put("p99Ms", duration.path("p(99)").asDouble());
        return metrics;
    }

    private List<Map<String, Object>> thresholdResults(JsonNode thresholds, Map<String, Object> metrics) {
        List<Map<String, Object>> results = new ArrayList<>();
        addThreshold(results, thresholds, "maxErrorRate", "errorRate", metrics, true);
        addThreshold(results, thresholds, "maxP90Ms", "p90Ms", metrics, true);
        addThreshold(results, thresholds, "maxP95Ms", "p95Ms", metrics, true);
        addThreshold(results, thresholds, "maxP99Ms", "p99Ms", metrics, true);
        addThreshold(results, thresholds, "minThroughputPerSecond", "throughputPerSecond", metrics, false);
        return results;
    }

    private void addThreshold(List<Map<String, Object>> results, JsonNode thresholds, String expectedKey,
                              String actualKey, Map<String, Object> metrics, boolean maximum) {
        if (!thresholds.has(expectedKey)) {
            return;
        }
        double expected = thresholds.path(expectedKey).asDouble();
        double actual = ((Number) metrics.get(actualKey)).doubleValue();
        boolean passed = maximum ? actual <= expected : actual >= expected;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", expectedKey);
        result.put("expected", expected);
        result.put("actual", actual);
        result.put("passed", passed);
        results.add(result);
    }

    private String outputTail(Path output) throws IOException {
        if (!Files.isRegularFile(output)) {
            return "无进程输出";
        }
        String content = Files.readString(output);
        return content.length() > 4000 ? content.substring(content.length() - 4000) : content;
    }

    private String statusReason(String processStatus, boolean thresholdsPassed) {
        if (!"PASSED".equals(processStatus)) {
            return "k6 执行失败";
        }
        return thresholdsPassed ? null : "压测阈值未满足";
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

    private String substitute(String template, Map<String, String> variables) {
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

    private Map<String, String> readStringMap(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, String.class));
        } catch (Exception e) {
            throw new ExecutionErrorException("环境数据反序列化失败", e);
        }
    }

    private List<String> readStringList(String json) {
        try {
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            throw new ExecutionErrorException("目标数据反序列化失败", e);
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
