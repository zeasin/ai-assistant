package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.LogService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LogController {

    private final LogService logService;

    public LogController(LogService logService) {
        this.logService = logService;
    }

    @GetMapping("/log")
    public String logPage(Model model) {
        model.addAttribute("logs", logService.load());
        return "1.0/log";
    }
}