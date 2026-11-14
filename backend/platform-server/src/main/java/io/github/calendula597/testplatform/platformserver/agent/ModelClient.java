package io.github.calendula597.testplatform.platformserver.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible chat completion client. The API key comes from the
 * environment only — never from source, prompts, cases or audit rows.
 */
@Component
public class ModelClient {

    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public ModelClient(@Value("${app.model.base-url:}") String baseUrl,
                       @Value("${app.model.api-key:}") String apiKey,
                       @Value("${app.model.name:}") String model) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    public boolean isConfigured() {
        return !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    public String model() {
        return model;
    }

    public record Completion(String content, int promptTokens, int completionTokens) {
    }

    public static class ModelException extends RuntimeException {
        public ModelException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @SuppressWarnings("unchecked")
    public Completion complete(String systemPrompt, String userPrompt) {
        if (!isConfigured()) {
            throw new ModelException("模型服务未配置", null);
        }
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userPrompt)));
            request.put("temperature", 0.2);

            Map<String, Object> response = RestClient.builder()
                    .baseUrl(baseUrl)
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .build()
                    .post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("choices") == null) {
                throw new ModelException("模型服务返回空响应", null);
            }
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String content = String.valueOf(message.get("content"));
            Map<String, Object> usage = (Map<String, Object>) response.getOrDefault(
                    "usage", Map.of("prompt_tokens", 0, "completion_tokens", 0));
            return new Completion(content,
                    ((Number) usage.getOrDefault("prompt_tokens", 0)).intValue(),
                    ((Number) usage.getOrDefault("completion_tokens", 0)).intValue());
        } catch (ModelException e) {
            throw e;
        } catch (Exception e) {
            throw new ModelException("模型服务调用失败：" + e.getMessage(), e);
        }
    }
}
