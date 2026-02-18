package com.example.influxdemo.controllers;

import com.example.influxdemo.models.DocFile;
import com.example.influxdemo.services.DocsService;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.example.influxdemo.services.GeminiHttpService;
import com.example.influxdemo.services.GeminiHttpService.GeminiResult;
import java.util.*;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.nio.charset.StandardCharsets;


@Controller
public class AdminChatController {

    private final DocsService docs;
    private final GeminiHttpService gemini;

    public AdminChatController(DocsService docs, GeminiHttpService gemini) {
        this.docs = docs;
        this.gemini = gemini;
    }

    @GetMapping("/admin/chat")
    public String chatPage(
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String prefill,
            @RequestParam(required = false, defaultValue = "0") String autoselect,
            Model model
    ) {
        String selectedArea = (area == null) ? "" : area.trim().toUpperCase();

        List<DocFile> available = docs.listDocsFor(selectedArea);

        model.addAttribute("active", "chat");
        model.addAttribute("selectedArea", selectedArea);
        model.addAttribute("docs", available);

        model.addAttribute("prefill", prefill == null ? "" : prefill);
        model.addAttribute("autoselect", autoselect);

        return "admin-chat";
    }

    @PostMapping("/admin/chat/upload")
    public String uploadDoc(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "GLOBAL") String scope,
            @RequestParam(required = false) String ambientArea
    ) {
        try {
            docs.saveUpload(file, scope, ambientArea);

            String area = (ambientArea == null) ? "" : ambientArea.trim();
            return "redirect:/admin/chat?ok=1&area=" + java.net.URLEncoder.encode(area, java.nio.charset.StandardCharsets.UTF_8);

        } catch (IllegalArgumentException e) {
            // This is your "AREA requires ambientArea" case (and empty file etc)
            String area = (ambientArea == null) ? "" : ambientArea.trim();
            return "redirect:/admin/chat?err=" + java.net.URLEncoder.encode(e.getMessage(), java.nio.charset.StandardCharsets.UTF_8)
                    + "&area=" + java.net.URLEncoder.encode(area, java.nio.charset.StandardCharsets.UTF_8);

        } catch (Exception e) {
            return "redirect:/admin/chat?err=Upload failed";
        }
    }

    @GetMapping("/admin/chat/docs/{id}")
    public ResponseEntity<Resource> viewDoc(@PathVariable Long id) {
        DocFile meta = docs.getMeta(id);
        Resource file = docs.getFile(id);

        MediaType mt;
        try {
            mt = (meta.getContentType() == null) ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(meta.getContentType());
        } catch (Exception e) {
            mt = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .contentType(mt)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + meta.getOriginalName() + "\"")
                .body(file);
    }

   @PostMapping(
    path = "/admin/chat/send-stream",
    consumes = MediaType.APPLICATION_JSON_VALUE,
    produces = "application/x-ndjson"
)
@ResponseBody
public ResponseEntity<StreamingResponseBody> sendStream(@RequestBody Map<String, Object> payload) {

    StreamingResponseBody stream = outputStream -> {
        ObjectMapper om = new ObjectMapper(); // single instance only

        try {
            String message = Objects.toString(payload.getOrDefault("message", ""), "").trim();
            String area = Objects.toString(payload.getOrDefault("area", ""), "").trim();

            @SuppressWarnings("unchecked")
            List<Object> selected = (List<Object>) payload.getOrDefault("docIds", List.of());

            List<Long> ids = selected.stream()
                    .map(x -> { try { return Long.parseLong(x.toString()); } catch (Exception e) { return null; } })
                    .filter(Objects::nonNull)
                    .toList();

            // Build doc pool
            List<DocFile> pool = ids.isEmpty()
                    ? docs.listDocsFor(area)
                    : ids.stream().map(docs::getMeta).toList();

            record MatchRow(DocFile doc, int score, String excerpt) {}

            Set<String> kw = Arrays.stream(message.toLowerCase().split("\\W+"))
                    .filter(t -> t.length() >= 4)
                    .collect(Collectors.toSet());

            List<MatchRow> ranked = new ArrayList<>();
            for (DocFile d : pool) {
                int sc = scoreDoc(message, d);

                if (!area.isBlank()
                        && "AREA".equalsIgnoreCase(d.getScope())
                        && d.getAmbientArea() != null
                        && area.equalsIgnoreCase(d.getAmbientArea())) {
                    sc += 2;
                }

                if (sc > 0) {
                    String excerpt = makeExcerpt(d.getExtractedText(), kw);
                    ranked.add(new MatchRow(d, sc, excerpt));
                }
            }

            ranked.sort((a, b) -> Integer.compare(b.score(), a.score()));
            List<MatchRow> top = ranked.stream().limit(3).toList();

            Set<Long> topIds = top.stream()
                    .map(x -> x.doc().getId())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            List<DocFile> other = pool.stream()
                    .filter(d -> d.getId() != null && !topIds.contains(d.getId()))
                    .toList();

            boolean isAreaSpecific = !area.isBlank() && (
                    message.toLowerCase().contains(area.toLowerCase()) ||
                    message.toLowerCase().contains("this area") ||
                    message.toLowerCase().contains("area ") ||
                    message.toLowerCase().contains("ambient")
            );

            List<DocFile> orderedPool = new ArrayList<>();
            orderedPool.addAll(top.stream().map(MatchRow::doc).toList());
            orderedPool.addAll(other);

            if (orderedPool.isEmpty()) {
                writeNdjson(outputStream, om, Map.of(
                        "type", "token",
                        "text", "No documents available to answer this question."
                ));
                writeNdjson(outputStream, om, Map.of(
                        "type", "done",
                        "case", "GLOBAL",
                        "topMatches", List.of(),
                        "otherDocs", List.of()
                ));
                outputStream.flush();
                return;
            }

            String prompt = buildPrompt(message, area, isAreaSpecific, orderedPool);

            GeminiResult r = gemini.generateText(prompt);
            String reply = r.ok() ? r.text() : ("AI ERROR: " + r.error());

            // Stream tokens
            for (String chunk : chunkText(reply, 28)) {
                writeNdjson(outputStream, om, Map.of("type", "token", "text", chunk));
                outputStream.flush();
                try { Thread.sleep(18); } catch (InterruptedException ignored) {}
            }

            // metadata
            List<Map<String, Object>> topMatches = top.stream().map(m -> Map.<String, Object>of(
                    "id", m.doc().getId(),
                    "name", m.doc().getOriginalName(),
                    "scope", m.doc().getScope(),
                    "ambientArea", m.doc().getAmbientArea(),
                    "score", m.score(),
                    "excerpt", m.excerpt()
            )).toList();

            List<Map<String, Object>> otherDocs = other.stream().map(d -> Map.<String, Object>of(
                    "id", d.getId(),
                    "name", d.getOriginalName(),
                    "scope", d.getScope(),
                    "ambientArea", d.getAmbientArea()
            )).toList();

            writeNdjson(outputStream, om, Map.of(
                    "type", "done",
                    "case", isAreaSpecific ? "AREA" : "GLOBAL",
                    "topMatches", topMatches,
                    "otherDocs", otherDocs
            ));
            outputStream.flush();

        } catch (Exception e) {
            try {
                writeNdjson(outputStream, om, Map.of(
                        "type", "error",
                        "message", "Server error: " + e.getMessage()
                ));
                outputStream.flush();
            } catch (Exception ignored) {
                // if we can't write to stream, there's nothing else we can do safely
            }
        }
    };

    return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/x-ndjson"))
            .body(stream);
}


    private static void writeNdjson(java.io.OutputStream os, ObjectMapper om, Map<String, Object> obj) throws Exception {
        String line = om.writeValueAsString(obj) + "\n";
        os.write(line.getBytes(StandardCharsets.UTF_8));
    }

    private static List<String> chunkText(String s, int size) {
        if (s == null) return List.of();
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            int end = Math.min(s.length(), i + size);
            out.add(s.substring(i, end));
            i = end;
        }
        return out;
    }

    // ---- Phase 1 "AI": basic matching + excerpts ----
    @PostMapping(
            path="/admin/chat/send",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    @ResponseBody
    public Map<String, Object> send(@RequestBody Map<String, Object> payload) {

        String message = Objects.toString(payload.getOrDefault("message", ""), "").trim();
        String area = Objects.toString(payload.getOrDefault("area", ""), "").trim();

        @SuppressWarnings("unchecked")
        List<Object> selected = (List<Object>) payload.getOrDefault("docIds", List.of());

        List<Long> ids = selected.stream()
                .map(x -> {
                    try { return Long.parseLong(x.toString()); } catch (Exception e) { return null; }
                })
                .filter(Objects::nonNull)
                .toList();

        // 1) Build doc pool: selected docs OR area/global pool
        List<DocFile> pool;
        if (!ids.isEmpty()) {
            pool = ids.stream().map(docs::getMeta).toList();
        } else {
            // listDocsFor(area) returns AREA(area) + GLOBAL
            // if area blank it returns GLOBAL only
            pool = docs.listDocsFor(area);
        }

        record MatchRow(DocFile doc, int score, String excerpt) {}

        Set<String> kw = Arrays.stream(message.toLowerCase().split("\\W+"))
                .filter(t -> t.length() >= 4)
                .collect(Collectors.toSet());

        List<MatchRow> ranked = new ArrayList<>();

        for (DocFile d : pool) {
            int sc = scoreDoc(message, d);

            // Small boost if doc is AREA-scoped and matches the selected area
            if (!area.isBlank()
                    && "AREA".equalsIgnoreCase(d.getScope())
                    && d.getAmbientArea() != null
                    && area.equalsIgnoreCase(d.getAmbientArea())) {
                sc += 2;
            }

            // Only treat as a "match" if it actually hits something
            if (sc > 0) {
                String excerpt = makeExcerpt(d.getExtractedText(), kw);
                ranked.add(new MatchRow(d, sc, excerpt));
            }
        }

        ranked.sort((a, b) -> Integer.compare(b.score(), a.score()));
        List<MatchRow> top = ranked.stream().limit(3).toList();

        // other docs = pool that are not in top
        Set<Long> topIds = top.stream()
                .map(x -> x.doc().getId())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<DocFile> other = pool.stream()
                .filter(d -> d.getId() != null && !topIds.contains(d.getId()))
                .toList();


        // 2) Decide "case" (your requirement)
        // If we have an area and the question looks area-specific, mark it AREA CASE
        boolean isAreaSpecific = !area.isBlank() && (
                message.toLowerCase().contains(area.toLowerCase()) ||
                message.toLowerCase().contains("this area") ||
                message.toLowerCase().contains("area ") ||
                message.toLowerCase().contains("ambient")
        );

        // 3) Build the prompt with safe truncation
        List<DocFile> orderedPool = new ArrayList<>();
        orderedPool.addAll(top.stream().map(MatchRow::doc).toList());
        orderedPool.addAll(other);

        String prompt = buildPrompt(message, area, isAreaSpecific, orderedPool);

        if (orderedPool.isEmpty()) {
            Map<String, Object> res = new HashMap<>();
            res.put("reply", "No documents available to answer this question.");
            res.put("topMatches", List.of());
            res.put("otherDocs", List.of());
            res.put("case", "GLOBAL");
            return res;
        }

        // 4) Call Gemini
        GeminiResult r = gemini.generateText(prompt);

        // 5) Response JSON for UI
        Map<String, Object> res = new HashMap<>();
        if (r.ok()) {
            res.put("reply", r.text());
        } else {
            res.put("reply", "AI ERROR: " + r.error());
        }

        // Also return which docs were used (so UI can show “sources used”)
        res.put("topMatches", top.stream().map(m -> Map.of(
                "id", m.doc().getId(),
                "name", m.doc().getOriginalName(),
                "scope", m.doc().getScope(),
                "ambientArea", m.doc().getAmbientArea(),
                "score", m.score(),
                "excerpt", m.excerpt()
        )).toList());

        res.put("otherDocs", other.stream().map(d -> Map.of(
                "id", d.getId(),
                "name", d.getOriginalName(),
                "scope", d.getScope(),
                "ambientArea", d.getAmbientArea()
        )).toList());


        res.put("case", isAreaSpecific ? "AREA" : "GLOBAL");
        return res;
        
    }

    private static String buildPrompt(String message, String area, boolean isAreaSpecific, List<DocFile> pool) {

        StringBuilder sb = new StringBuilder();

        sb.append("You are an on-call operations assistant for a data-center monitoring system.\n");
        sb.append("Your job is to give clear, step-by-step instructions to an admin.\n");
        sb.append("Use ONLY the provided documents as the source of truth.\n");
        sb.append("If the documents do not contain the answer, say what is missing and ask a short follow-up question.\n\n");

        sb.append("CASE TYPE: ").append(isAreaSpecific ? "AREA" : "GLOBAL").append("\n");
        if (!area.isBlank()) {
            sb.append("AMBIENT AREA: ").append(area).append("\n");
        }
        sb.append("\n");

        sb.append("USER QUESTION:\n");
        sb.append(message).append("\n\n");

        sb.append("DOCUMENTS PROVIDED:\n");

        int totalBudget = 25_000; // total chars across docs (safe cap)
        int perDocBudget = 6_000; // per doc cap
        int used = 0;

        for (int i = 0; i < pool.size(); i++) {
            DocFile d = pool.get(i);

            String text = d.getExtractedText();
            if (text == null) text = "";
            text = normalizeWs(text);

            String clipped = clip(text, perDocBudget);

            // stop if over total budget
            if (used + clipped.length() > totalBudget) {
                int remaining = totalBudget - used;
                if (remaining <= 0) break;
                clipped = clip(clipped, remaining);
            }

            used += clipped.length();

            sb.append("\n--- DOC ").append(i + 1).append(" ---\n");
            sb.append("Name: ").append(d.getOriginalName()).append("\n");
            sb.append("Scope: ").append(d.getScope());
            if (d.getAmbientArea() != null && !d.getAmbientArea().isBlank()) {
                sb.append(" (").append(d.getAmbientArea()).append(")");
            }
            sb.append("\n");
            sb.append("Content:\n");
            sb.append(clipped).append("\n");
        }

        sb.append("\nRESPONSE FORMAT RULES:\n");
        sb.append("- Start with a short diagnosis (1-2 lines).\n");
        sb.append("- Then give a numbered action checklist.\n");
        sb.append("- If AREA CASE and area-specific docs exist, prioritize them; otherwise use GLOBAL.\n");
        sb.append("- If something is unknown, ask at most 2 follow-up questions.\n");

        return sb.toString();
    }

    private static String clip(String s, int maxChars) {
        if (s == null) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "\n...[TRUNCATED]...";
    }

    private static String normalizeWs(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String makeExcerpt(String text, Set<String> kw) {
        if (text == null || text.isBlank() || kw.isEmpty()) return "";
        String lower = text.toLowerCase();

        for (String k : kw) {
            int idx = lower.indexOf(k);
            if (idx >= 0) {
                int start = Math.max(0, idx - 80);
                int end = Math.min(text.length(), idx + 180);
                return text.substring(start, end).replaceAll("\\s+", " ").trim();
            }
        }
        return text.substring(0, Math.min(200, text.length())).replaceAll("\\s+", " ").trim();
    }

    private static int scoreDoc(String message, DocFile d) {
        if (message == null) message = "";
        String text = d.getExtractedText();
        if (text == null) text = "";

        String[] tokens = message.toLowerCase().split("\\W+");
        Set<String> kw = Arrays.stream(tokens)
                .filter(t -> t.length() >= 4)
                .collect(Collectors.toSet());

        String lower = text.toLowerCase();
        int score = 0;
        for (String k : kw) {
            if (lower.contains(k)) score++;
        }
        return score;
    }
    
}
