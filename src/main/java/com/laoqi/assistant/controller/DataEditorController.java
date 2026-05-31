package com.laoqi.assistant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class DataEditorController {

    @GetMapping("/data-editor")
    public String dataEditorPage(@RequestParam(required = false, defaultValue = "customer") String type) {
        return "data_editor";
    }
}
