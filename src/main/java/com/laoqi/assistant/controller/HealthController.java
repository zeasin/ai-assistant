package com.laoqi.assistant.controller;

import com.laoqi.assistant.util.TimeUtil;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "version", "2.0.0", "time", TimeUtil.nowStr());
    }
}