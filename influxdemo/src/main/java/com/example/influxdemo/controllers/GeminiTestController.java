package com.example.influxdemo.controllers;

import com.example.influxdemo.services.GeminiHttpService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GeminiTestController {

    private final GeminiHttpService gemini;

    public GeminiTestController(GeminiHttpService gemini) {
        this.gemini = gemini;
    }

    @GetMapping("/api/ai/test")
    public String test(@RequestParam(defaultValue = "Say hello in 1 short sentence") String prompt) {
        return gemini.generate(prompt);
    }
}
