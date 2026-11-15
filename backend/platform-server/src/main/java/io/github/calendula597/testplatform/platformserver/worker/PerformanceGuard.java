package io.github.calendula597.testplatform.platformserver.worker;

import io.github.calendula597.testplatform.platformserver.environment.TargetAllowlist;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class PerformanceGuard {

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([A-Za-z0-9_.-]+)}}");

    private final TargetAllowlist targetAllowlist;
    private final ObjectMapper objectMapper;
    private final int maxVus;
    private final int maxDurationSeconds;

    public PerformanceGuard(TargetAllowlist targetAllowlist, ObjectMapper objectMapper,
                            @Value("${app.execution.performance-max-vus:8}") int maxVus,
                            @Value("${app.execution.performance-max-duration-seconds:30}") int maxDurationSeconds) {
        this.targetAllowlist = targetAllowlist;
        this.objectMapper = objectMapper;
        this.maxVus = maxVus;
        this.maxDurationSeconds = maxDurationSeconds;
    }

    public void validate(String executionJson, List<String> targets, Map<String, String> variables) {
        JsonNode spec = readJson(executionJson);
        validateSchema(spec);
        String target = substitute(spec.path("target").asText(""), variables);
        TargetAllowlist.Check check = targetAllowlist.check(targets, target);
        if (!check.allowed()) {
            throw new IllegalArgumentException("压测目标不在白名单: " + target);
        }
    }

    public void validateSchema(JsonNode spec) {
        if (!spec.path("target").isTextual() || spec.path("target").asText().isBlank()) {
            throw new IllegalArgumentException("压测目标不能为空");
        }
        if (!spec.path("stages").isArray() || spec.path("stages").isEmpty()) {
            throw new IllegalArgumentException("压测必须至少包含一个阶段");
        }
        if (spec.path("stages").size() > 5) {
            throw new IllegalArgumentException("压测阶段最多 5 个");
        }
        int totalDuration = 0;
        for (JsonNode stage : spec.path("stages")) {
            int duration = stage.path("durationSeconds").asInt(0);
            int target = stage.path("target").asInt(0);
            if (duration < 1) {
                throw new IllegalArgumentException("每个压测阶段必须至少持续 1 秒");
            }
            if (target < 1 || target > maxVus) {
                throw new IllegalArgumentException("每阶段并发必须是 1-" + maxVus);
            }
            totalDuration += duration;
        }
        if (totalDuration > maxDurationSeconds) {
            throw new IllegalArgumentException("压测总时长不能超过 " + maxDurationSeconds + " 秒");
        }
        JsonNode thresholds = spec.path("thresholds");
        if (!thresholds.isObject()) {
            throw new IllegalArgumentException("必须配置压测阈值");
        }
        validateRate(thresholds, "maxErrorRate");
        validateMilliseconds(thresholds, "maxP90Ms");
        validateMilliseconds(thresholds, "maxP95Ms");
        validateMilliseconds(thresholds, "maxP99Ms");
        validateMilliseconds(thresholds, "minThroughputPerSecond");
    }

    private void validateRate(JsonNode thresholds, String field) {
        if (thresholds.has(field)) {
            double value = thresholds.path(field).asDouble(-1);
            if (value < 0 || value > 1) {
                throw new IllegalArgumentException(field + " 必须在 0-1 之间");
            }
        }
    }

    private void validateMilliseconds(JsonNode thresholds, String field) {
        if (thresholds.has(field) && thresholds.path(field).asDouble(-1) < 0) {
            throw new IllegalArgumentException(field + " 不能为负数");
        }
    }

    private JsonNode readJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("压测配置无法解析", e);
        }
    }

    private String substitute(String template, Map<String, String> variables) {
        String result = template;
        var matcher = VARIABLE.matcher(template);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!variables.containsKey(key)) {
                throw new IllegalArgumentException("变量未定义: " + key);
            }
            result = result.replace("{{" + key + "}}", variables.get(key));
        }
        return result;
    }
}
