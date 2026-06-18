package com.laoqi.assistant.controller;

import com.laoqi.assistant.service.KnowledgeBaseService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class IndexController {

    private final KnowledgeBaseService kbService;

    public IndexController(KnowledgeBaseService kbService) {
        this.kbService = kbService;
    }

    @GetMapping("/")
    public String index() {
        // 首页重定向到聊天窗口
        var firstKb = kbService.getFirst();
        if (firstKb != null) {
            return "redirect:/chat?kbId=" + firstKb.getId();
        }
        return "redirect:/config";
    }
}
