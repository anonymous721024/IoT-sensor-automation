package com.example.influxdemo.dto;

public record AdminCommand(
        String action,   // ADD, REMOVE, SET, LIST, LOW_STOCK, UNKNOWN
        String name,     // medicine name
        Integer quantity,
        String expiry,    // DD-MM-YYYY or null
        Double price
) {}
