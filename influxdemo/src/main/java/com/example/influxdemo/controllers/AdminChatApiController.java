package com.example.influxdemo.controllers;

import com.example.influxdemo.services.AdminInventoryCommandService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/chat")
public class AdminChatApiController {

    private final AdminInventoryCommandService commands;

    public AdminChatApiController(AdminInventoryCommandService commands) {
        this.commands = commands;
    }

    @PostMapping("/send")
    public String send(@RequestBody ChatRequest req) {
        try {
            return commands.handle(req.message());
        } catch (IllegalArgumentException e) {
            return "⚠️ " + e.getMessage();
        } catch (Exception e) {
            return "⚠️ Something went wrong. Check the server logs.";
        }
    }
    
    public record ChatRequest(String message) {}
}
