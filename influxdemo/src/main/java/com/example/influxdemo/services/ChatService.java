package com.example.influxdemo.services;

import com.example.influxdemo.services.InventorySummaryService.MedicineSummary;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ChatService {

    private final InventorySummaryService summaryService;
    private final GeminiHttpService gemini;

    public ChatService(InventorySummaryService summaryService, GeminiHttpService gemini) {
        this.summaryService = summaryService;
        this.gemini = gemini;
    }

    public String replyToUser(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "Please type a message.";
        }

        String msg = userMessage.toLowerCase(Locale.ROOT);

        // Always have inventory snapshot available (for prompt context)
        Map<String, MedicineSummary> summary = summaryService.getSummary();
        var items = summary.values().stream()
                .sorted((a,b) -> Integer.compare(b.totalStock(), a.totalStock()))
                .toList();

        int limit = 30;
        String inventoryList = items.stream()
                .limit(limit)
                .map(m -> m.name() + " (stock: " + m.totalStock() + ")")
                .collect(Collectors.joining("\n"));

        if (items.size() > limit) {
            inventoryList += "\n(and " + (items.size() - limit) + " more...)";
        }

        // Inventory-only queries (no Gemini)
        if (msg.contains("in stock") || msg.contains("available") || msg.contains("what do you have") || msg.contains("inventory")) {
            if (inventoryList.isBlank()) return "Right now we don’t have any items recorded in stock.";
            return "Here’s what’s in stock right now:\n" + inventoryList;
        }

        // Everything else -> Gemini ONLY
        String prompt = buildUserPrompt(userMessage, inventoryList);
        GeminiHttpService.GeminiResult gr = gemini.generateText(prompt);

        if (!gr.ok()) {
            // IMPORTANT: do not hardcode medical advice here
            // Just give a clean message and keep stock visible.
            return """
            Sorry — I couldn’t reach the AI assistant right now. Please try again.

            In stock right now:
            %s
            """.formatted(inventoryList.isBlank() ? "(no items found)" : inventoryList).trim();
        }

        String aiText = (gr.text() == null) ? "" : gr.text().trim();
        if (aiText.isBlank()) {
            return """
            Sorry — the AI assistant returned an empty response. Please try again.

            In stock right now:
            %s
            """.formatted(inventoryList.isBlank() ? "(no items found)" : inventoryList).trim();
        }

        return aiText;
    }

    private String buildUserPrompt(String userMessage, String inventoryList) {
        return """
        You are a pharmacy assistant chatbot for end users.

        Safety rules:
        - Do NOT diagnose.
        - Provide general OTC guidance only.
        - Always include a short disclaimer to consult a doctor/pharmacist.
        - Do not invent medicines. Only mention medicines that appear in the inventory list as "available".
        - If urgent danger signs exist (chest pain, breathing difficulty, severe allergic reaction, stroke signs, severe bleeding), advise urgent care.

        Formatting constraints:
        - No asterisks.
        - Use short paragraphs separated by blank lines.
        - Plain text only.

        Inventory right now (authoritative):
        %s

        User message:
        %s

        Reply in plain text.
        """.formatted(
                inventoryList.isBlank() ? "(no items)" : inventoryList,
                userMessage
        );
    }
}
