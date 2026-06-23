package com.laoqi.assistant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class CollectorPageController {

    @GetMapping("/data/collector")
    public String collectorPage() {
        return "1.0/collector";
    }
}