package com.laoqi.assistant.datacenter;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DataCenterPageController {

    @GetMapping("/datacenter")
    public String dataCenterPage() {
        return "datacenter";
    }
}
