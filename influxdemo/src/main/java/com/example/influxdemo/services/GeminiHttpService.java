package com.example.influxdemo.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@Service
public class GeminiHttpService {

    private static final Logger log = LoggerFactory.getLogger(GeminiHttpService.class);

    private static final String ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";

    private final String apiKeyProp;
    private final HttpClient http;
    private final ObjectMapper om = new ObjectMapper();

    public record GeminiResult(boolean ok, String text, String error) {}

    public GeminiHttpService(@Value("${spring.ai.google.genai.api-key:}") String apiKeyProp) {
        this.apiKeyProp = apiKeyProp;

        this.http = HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .build();
    }

    private String apiKey() {
        if (apiKeyProp != null && !apiKeyProp.isBlank()) return apiKeyProp.trim();
        String env = System.getenv("GEMINI_API_KEY");
        return (env == null) ? "" : env.trim();
    }

    public GeminiResult generateText(String prompt) {
        try {
            String key = apiKey();
            if (key.isBlank()) {
                return new GeminiResult(false, null,
                        "No API key found. Set spring.ai.google.genai.api-key OR GEMINI_API_KEY.");
            }

            String body = """
                {
                  "contents": [
                    { "parts": [ { "text": %s } ] }
                  ]
                }
                """.formatted(om.writeValueAsString(prompt));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .header("x-goog-api-key", key)
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            int code = resp.statusCode();
            String respBody = resp.body();

            if (code == 503) {
                log.warn("Gemini overloaded (503). body={}", respBody);
                return new GeminiResult(false, null, "MODEL_OVERLOADED (503)");
            }
            if (code == 429) {
                log.warn("Gemini rate limited (429). body={}", respBody);
                return new GeminiResult(false, null, "RATE_LIMITED (429)");
            }
            if (code >= 400) {
                log.error("Gemini HTTP error {}. body={}", code, respBody);
                return new GeminiResult(false, null, "Gemini HTTP " + code + " body=" + trim(respBody));
            }

            JsonNode root = om.readTree(respBody);
            JsonNode textNode = root.path("candidates").path(0)
                    .path("content").path("parts").path(0).path("text");

            String out = textNode.isMissingNode() ? "" : textNode.asText();
            if (out.isBlank()) {
                log.warn("Gemini returned empty text. fullResponse={}", respBody);
            }
            return new GeminiResult(true, out, null);

        } catch (Exception e) {
            log.error("Gemini exception", e);
            return new GeminiResult(false, null,
                    "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ✅ BACKWARDS-COMPATIBLE: used by AdminInventoryCommandService repair attempt
    public String generate(String prompt) {
        GeminiResult r = generateText(prompt);
        if (r.ok()) return r.text();
        return "ERROR: " + r.error();
    }

    // ✅ BACKWARDS-COMPATIBLE: used by AdminInventoryCommandService main parse
    public String classifyAdminCommand(String userInput) {
        String prompt = """
            You are a strict JSON command parser for a pharmacy inventory admin tool.

            Return ONLY a single JSON object. No markdown. No explanation. No extra text.

            Allowed actions: ADD, REMOVE, SET, LIST, LOW_STOCK, UPDATE_PRICE, UNKNOWN

            Rules:
            - quantity MUST be an integer number. Convert words to numbers (e.g., "five" -> 5).
            - expiry MUST be DD-MM-YYYY. If user gives YYYY-MM-DD, convert it to DD-MM-YYYY.
            - If expiry is missing for ADD, set expiry to null.
            - price MUST be a number (double). If missing, null.
            - If user asks "what's in stock", action=LIST.
            - If user asks "low stock" optionally with a number, action=LOW_STOCK and quantity=threshold.
            - If user asks to change price, action=UPDATE_PRICE and price must be set.

            Output schema:
            {"action":"...","name":"...","quantity":123,"expiry":"DD-MM-YYYY","price":12.34}

            Input:
            """ + userInput;

        GeminiResult r = generateText(prompt);
        if (r.ok()) return r.text();

        // fallback JSON so AdminInventoryCommandService can parse safely
        return "{\"action\":\"UNKNOWN\",\"name\":null,\"quantity\":null,\"expiry\":null,\"price\":null}";
    }

    private static String trim(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 400 ? s.substring(0, 400) + "..." : s;
    }
}
