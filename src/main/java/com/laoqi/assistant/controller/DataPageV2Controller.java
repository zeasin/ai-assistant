package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.KnowledgeBaseService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DataPageV2Controller {

    private final KnowledgeBaseService kbService;

    public DataPageV2Controller(KnowledgeBaseService kbService) {
        this.kbService = kbService;
    }

    @GetMapping("/data")
    public String dataPage(@RequestParam(required = false) Long kbId, Model model) {
        if (kbId != null) {
            model.addAttribute("kbId", kbId);
            var kb = kbService.getById(kbId);
            if (kb != null) model.addAttribute("currentKb", kb);
        }
        return "2.0/data";
    }
}
