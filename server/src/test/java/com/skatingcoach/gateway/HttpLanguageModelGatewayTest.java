package com.skatingcoach.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HttpLanguageModelGatewayTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void unconfiguredProviderNeverPretendsToGenerate() {
        var gateway = new HttpLanguageModelGateway(mapper, "", "", "", 5);
        assertThat(gateway.available()).isFalse();
        assertThatThrownBy(() -> gateway.generate("system", "input"))
                .isInstanceOfSatisfying(ModelGatewayException.class,
                        error -> assertThat(error.code()).isEqualTo("PROVIDER_UNAVAILABLE"));
    }

    @Test void sendsRealWireRequestAndReadsUsageWithoutLoggingSecrets() throws Exception {
        var captured = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"model\":\"test-model\",\"choices\":[{\"message\":{\"content\":\"教练回应\"}}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var gateway = new HttpLanguageModelGateway(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions", "unit-test-credential", "test-model", 5);
            var reply = gateway.generate("教练指令", "本场求稳");
            assertThat(reply.text()).isEqualTo("教练回应");
            assertThat(reply.inputTokens()).isEqualTo(12);
            assertThat(authorization.get()).isEqualTo("Bearer unit-test-credential");
            assertThat(mapper.readTree(captured.get()).path("messages").get(1).path("content").asText()).isEqualTo("本场求稳");
        } finally { server.stop(0); }
    }

    @Test void rejectsNonTlsRemoteEndpoints() {
        assertThatThrownBy(() -> new HttpLanguageModelGateway(mapper, "http://example.com/chat/completions", "test", "test", 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
