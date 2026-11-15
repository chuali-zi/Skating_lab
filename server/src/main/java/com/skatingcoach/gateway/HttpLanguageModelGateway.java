package com.skatingcoach.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Server-side adapter for providers exposing the chat-completions HTTP wire format. */
@Component
public class HttpLanguageModelGateway implements LanguageModelGateway {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;
    private final boolean jsonMode;
    private final int maxOutputTokens;

    public HttpLanguageModelGateway(ObjectMapper mapper,
            @Value("${skating.llm.endpoint:}") String endpoint,
            @Value("${skating.llm.api-key:}") String apiKey,
            @Value("${skating.llm.model:}") String model,
            @Value("${skating.llm.timeout-seconds:90}") long timeoutSeconds,
            @Value("${skating.llm.json-mode:false}") boolean jsonMode,
            @Value("${skating.llm.max-output-tokens:0}") int maxOutputTokens) {
        this.mapper = mapper;
        this.endpoint = endpoint.strip();
        this.apiKey = apiKey.strip();
        this.model = model.strip();
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.jsonMode = jsonMode;
        this.maxOutputTokens = maxOutputTokens;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        if (!this.endpoint.isEmpty()) {
            URI uri = URI.create(this.endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme()) &&
                    !("http".equalsIgnoreCase(uri.getScheme()) &&
                            List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost()))) {
                throw new IllegalArgumentException("LLM endpoint must use HTTPS or loopback HTTP");
            }
            if (uri.getUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("LLM endpoint must not contain user info or fragments");
            }
        }
    }

    @Override
    public boolean available() { return !endpoint.isEmpty() && !apiKey.isEmpty() && !model.isEmpty(); }

    @Override
    public ModelReply generate(String systemInstruction, String userInput) {
        if (!available()) throw new ModelGatewayException("PROVIDER_UNAVAILABLE", "尚未配置云端模型。");
        try {
            Map<String,Object> payload = new java.util.LinkedHashMap<>(Map.of("model", model, "messages", List.of(
                    Map.of("role", "system", "content", systemInstruction),
                    Map.of("role", "user", "content", userInput))));
            if (jsonMode) payload.put("response_format", Map.of("type", "json_object"));
            if (maxOutputTokens > 0) payload.put("max_tokens", maxOutputTokens);
            String body = mapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ModelGatewayException("PROVIDER_ERROR", "云端模型暂时无法处理请求。");
            }
            JsonNode parsed = mapper.readTree(response.body());
            if ("length".equals(parsed.path("choices").path(0).path("finish_reason").asText())) {
                throw new ModelGatewayException("PROVIDER_OUTPUT_TRUNCATED", "云端输出达到长度上限，未发布不完整结果。请调整服务端输出配置后重试。");
            }
            JsonNode content = parsed.path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || content.asText().isBlank()) {
                throw new ModelGatewayException("INVALID_PROVIDER_RESPONSE", "云端模型没有返回可用内容。");
            }
            return new ModelReply(content.asText(), parsed.path("model").asText(model),
                    parsed.path("usage").path("prompt_tokens").asLong(0),
                    parsed.path("usage").path("completion_tokens").asLong(0));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ModelGatewayException("PROVIDER_INTERRUPTED", "模型请求已中断。");
        } catch (IOException error) {
            throw new ModelGatewayException("PROVIDER_UNAVAILABLE", "暂时无法连接云端模型，输入已经保留。");
        }
    }
}
