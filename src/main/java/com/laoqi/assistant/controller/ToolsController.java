package com.laoqi.assistant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class ToolsController {

    @GetMapping("/tools")
    public String toolsPage() {
        return "tools";
    }
}
