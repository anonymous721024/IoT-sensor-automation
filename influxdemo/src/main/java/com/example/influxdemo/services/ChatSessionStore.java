package com.example.influxdemo.services;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.time.LocalDateTime;
import java.util.*;

@Component
@SessionScope
public class ChatSessionStore {

    public static class Message {
        public String role; // USER or AI
        public String text;
        public LocalDateTime time;

        public Message() {}
        public Message(String role, String text) {
            this.role = role;
            this.text = text;
            this.time = LocalDateTime.now();
        }
    }

    public static class ThreadSummary {
        public String id;
        public String title;
        public LocalDateTime updatedAt;

        public ThreadSummary(String id, String title, LocalDateTime updatedAt) {
            this.id = id;
            this.title = title;
            this.updatedAt = updatedAt;
        }
    }

    private final Map<String, List<Message>> threads = new LinkedHashMap<>();
    private final Map<String, String> titles = new HashMap<>();
    private final Map<String, LocalDateTime> updatedAt = new HashMap<>();

    public String createThread() {
        String id = UUID.randomUUID().toString();
        threads.put(id, new ArrayList<>());
        titles.put(id, "New chat");
        updatedAt.put(id, LocalDateTime.now());
        return id;
    }

    public List<Message> getThread(String id) {
        return threads.getOrDefault(id, new ArrayList<>());
    }

    public void addMessage(String threadId, String role, String text) {
        threads.computeIfAbsent(threadId, k -> new ArrayList<>()).add(new Message(role, text));
        updatedAt.put(threadId, LocalDateTime.now());

        // set title from first USER message if still default
        if ("New chat".equals(titles.getOrDefault(threadId, "New chat")) && "USER".equals(role)) {
            String t = text.trim();
            if (t.length() > 32) t = t.substring(0, 32) + "…";
            titles.put(threadId, t.isBlank() ? "New chat" : t);
        }
    }

    public List<ThreadSummary> listThreads() {
        // most recent first
        List<ThreadSummary> list = new ArrayList<>();
        for (String id : threads.keySet()) {
            list.add(new ThreadSummary(
                    id,
                    titles.getOrDefault(id, "New chat"),
                    updatedAt.getOrDefault(id, LocalDateTime.now())
            ));
        }
        list.sort((a, b) -> b.updatedAt.compareTo(a.updatedAt));
        return list;
    }
}
