package com.laoqi.assistant.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DataImportController {

    @GetMapping("/data/import")
    public String importPage() {
        return "data_import";
    }
}
