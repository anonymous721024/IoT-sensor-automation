package com.example.influxdemo.dto;

/**
 * Represents a message sent from the chat UI to the backend
 */
public class ChatRequest {
    private String message;

    public ChatRequest() {}

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
