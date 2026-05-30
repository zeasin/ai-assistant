package com.laoqi.assistant.controller;

import com.laoqi.assistant.config.AppConfig;
import com.laoqi.assistant.config.PortHealthChecker;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

@ControllerAdvice
public class GlobalModelAdvice {

    private final AppConfig appConfig;

    public GlobalModelAdvice(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    @ModelAttribute("requestURI")
    public String requestURI(HttpServletRequest request) {
        return request.getRequestURI();
    }

    @ModelAttribute("notesRunning")
    public boolean notesRunning() {
        return PortHealthChecker.notesRunning;
    }

    @ModelAttribute("codeRunning")
    public boolean codeRunning() {
        return PortHealthChecker.codeRunning;
    }

    @ModelAttribute("notesPort")
    public int notesPort() {
        return appConfig.getNotesPort();
    }

    @ModelAttribute("codePort")
    public int codePort() {
        return appConfig.getCodePort();
    }
}
