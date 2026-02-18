package com.example.influxdemo.dto;

/**
 * Represents a message sent back to the chat UI
 */
public class ChatResponse {
    private String reply;

    public ChatResponse(String reply) {
        this.reply = reply;
    }

    public String getReply() {
        return reply;
    }
}
