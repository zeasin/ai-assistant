package com.laoqi.assistant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HelpController {

    @GetMapping("/help")
    public String helpPage() {
        return "1.0/help";
    }
}