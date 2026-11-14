package io.github.calendula597.testplatform.platformserver.knowledge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI-compatible embedding client. When no provider is configured the
 * caller receives an explicit "not configured" state — never a silent
 * fallback pretending embeddings exist.
 */
@Component
public class EmbeddingClient {

    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public EmbeddingClient(@Value("${app.embedding.base-url:}") String baseUrl,
                           @Value("${app.embedding.api-key:}") String apiKey,
                           @Value("${app.embedding.model:}") String model) {
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

    public record EmbeddingResult(String model, List<float[]> vectors) {
    }

    public Optional<EmbeddingResult> embed(List<String> texts) {
        if (!isConfigured()) {
            return Optional.empty();
        }
        try {
            Map<String, Object> request = new HashMap<>();
            request.put("model", model);
            request.put("input", texts);
            Map<String, Object> response = RestClient.builder()
                    .baseUrl(baseUrl)
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .build()
                    .post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Map.class);
            if (response == null || response.get("data") == null) {
                throw new IllegalStateException("嵌入服务返回空响应");
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            List<float[]> vectors = data.stream()
                    .map(d -> toVector((List<Number>) d.get("embedding")))
                    .toList();
            return Optional.of(new EmbeddingResult(model, vectors));
        } catch (Exception e) {
            throw new EmbeddingException("嵌入服务调用失败：" + e.getMessage(), e);
        }
    }

    private float[] toVector(List<Number> numbers) {
        if (numbers == null) {
            throw new IllegalStateException("嵌入服务未返回向量");
        }
        float[] vector = new float[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) {
            vector[i] = numbers.get(i).floatValue();
        }
        return vector;
    }

    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
