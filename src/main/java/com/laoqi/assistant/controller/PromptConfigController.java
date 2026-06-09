package com.laoqi.assistant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PromptConfigController {

    @GetMapping("/prompts")
    public String promptsPage() {
        return "prompts";
    }
}