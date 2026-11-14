package com.skatingcoach.api;

import com.skatingcoach.gateway.LanguageModelGateway;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final LanguageModelGateway gateway;

    public HealthController(LanguageModelGateway gateway) { this.gateway = gateway; }

    @GetMapping("/api/v1/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "service", "skating-coach", "modelConfigured", gateway.available());
    }
}
