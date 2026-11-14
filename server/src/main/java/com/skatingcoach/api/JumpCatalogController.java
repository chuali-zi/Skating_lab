package com.skatingcoach.api;

import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JumpCatalogController {
    @GetMapping("/api/v1/catalog/jumps")
    public List<JumpOption> jumps() {
        List<JumpOption> result = new ArrayList<>();
        String[][] types = {{"T", "后外点冰"}, {"S", "后内结环"}, {"Lo", "后外结环"},
                {"F", "后内点冰"}, {"Lz", "勾手"}, {"A", "阿克塞尔"}};
        for (int rotations = 1; rotations <= 4; rotations++) {
            for (String[] type : types) result.add(new JumpOption(rotations + type[0], type[1], rotations));
        }
        result.add(new JumpOption("1Eu", "Euler 连接跳", 1));
        return List.copyOf(result);
    }

    public record JumpOption(String code, String label, int revolutions) {}
}
