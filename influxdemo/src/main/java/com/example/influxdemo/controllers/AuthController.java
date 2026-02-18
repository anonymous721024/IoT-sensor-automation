package com.example.influxdemo.controllers;

import com.example.influxdemo.models.AppUser;
import com.example.influxdemo.repositories.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
public class AuthController {

    private final AppUserRepository repo;
    private final PasswordEncoder encoder;

    public AuthController(AppUserRepository repo, PasswordEncoder encoder) {
        this.repo = repo;
        this.encoder = encoder;
    }

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }

    @GetMapping("/signup")
    public String signupPage() {
        return "signup";
    }

    @PostMapping("/signup")
    public String doSignup(@RequestParam String username,
                           @RequestParam String password,
                           Model model) {

        String u = (username == null) ? "" : username.trim();
        if (u.isBlank() || password == null || password.length() < 6) {
            model.addAttribute("error", "Username required and password must be at least 6 characters.");
            return "signup";
        }

        if (repo.existsByUsername(u)) {
            model.addAttribute("error", "Username already exists.");
            return "signup";
        }

        repo.save(new AppUser(u, encoder.encode(password)));
        return "redirect:/login?signup=ok";
    }
}
