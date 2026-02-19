package com.example.influxdemo.controllers;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.influxdemo.data.InfluxClient;

@RestController
public class InfluxController {
    
    private final InfluxClient influxClient;

    public InfluxController(InfluxClient influxClient) {
        this.influxClient = influxClient;
    }

    @GetMapping("/check-influx")
    public String checkInflux() {
        return influxClient.health(); // should return "OK"
    }
}
