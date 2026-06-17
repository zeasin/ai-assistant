package com.laoqi.assistant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class CollectorPageController {

    @GetMapping("/collector")
    public String collectorPage() {
        return "collector";
    }
}