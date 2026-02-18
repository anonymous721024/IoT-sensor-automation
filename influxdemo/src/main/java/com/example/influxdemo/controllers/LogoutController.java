package com.example.influxdemo.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class LogoutController {
    @GetMapping("/logout")
    public String logout() {
        // placeholder: if you add Spring Security later, remove this controller
        return "redirect:/";
    }
}
