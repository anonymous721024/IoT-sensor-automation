package com.example.influxdemo.services;

import com.example.influxdemo.dto.AdminCommand;
import com.example.influxdemo.services.InventorySummaryService.MedicineSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AdminInventoryCommandService {

    private final InventoryService inventoryService;
    private final InventorySummaryService summaryService;
    private final GeminiHttpService gemini;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final java.util.regex.Pattern DMY =
        java.util.regex.Pattern.compile("^\\d{2}-\\d{2}-\\d{4}$");

    private String normalizeExpiry(String expiry) {
        if (expiry == null) return null;
        String e = expiry.trim();
    
        // If YYYY-MM-DD -> DD-MM-YYYY
        if (e.matches("^\\d{4}-\\d{2}-\\d{2}$")) {
            String[] parts = e.split("-");
            return parts[2] + "-" + parts[1] + "-" + parts[0];
        }
        return e;
    }

    private AdminCommand normalize(AdminCommand cmd) {
        if (cmd == null) return null;

        String action = (cmd.action() == null) ? null : cmd.action().trim().toUpperCase();
        String name = (cmd.name() == null) ? null : cleanName(cmd.name());

        Integer qty = cmd.quantity();
        if (qty != null && qty < 0 && !"REMOVE".equals(action)) {
            // only REMOVE can implicitly represent subtraction (but we already store removal separately)
            // keep qty as-is; we'll validate later
        }

        String expiry = normalizeExpiry(cmd.expiry());
        Double price = cmd.price();

        return new AdminCommand(action, name, qty, expiry, price);
    }
    
    private String validate(AdminCommand cmd) {
        if (cmd == null || cmd.action() == null) return "Missing action.";

        switch (cmd.action()) {
            case "ADD" -> {
                if (cmd.name() == null || cmd.name().isBlank()) return "Medicine name required.";
                if (cmd.quantity() == null || cmd.quantity() <= 0) return "Quantity must be > 0.";
                if (cmd.expiry() == null || cmd.expiry().isBlank()) return "Expiry required (DD-MM-YYYY).";
                if (!DMY.matcher(cmd.expiry()).matches()) return "Expiry must be DD-MM-YYYY (e.g., 14-12-2027).";
                if (cmd.price() != null && cmd.price() < 0) return "Price must be >= 0.";
            }
            case "REMOVE" -> {
                if (cmd.name() == null || cmd.name().isBlank()) return "Medicine name required.";
                if (cmd.quantity() == null || cmd.quantity() <= 0) return "Quantity must be > 0.";
            }
            case "SET" -> {
                if (cmd.name() == null || cmd.name().isBlank()) return "Medicine name required.";
                if (cmd.quantity() == null || cmd.quantity() < 0) return "Quantity must be 0 or more.";
                // expiry optional for SET in your design — ok
                if (cmd.expiry() != null && !cmd.expiry().isBlank() && !DMY.matcher(cmd.expiry()).matches()) {
                    return "Expiry must be DD-MM-YYYY (e.g., 14-12-2027).";
                }
                if (cmd.price() != null && cmd.price() < 0) return "Price must be >= 0.";
            }
            case "LOW_STOCK" -> {
                if (cmd.quantity() != null && cmd.quantity() < 0) return "Threshold must be >= 0.";
            }
            case "LIST" -> {
                // No validation needed
            }
            
            case "UPDATE_PRICE" -> {
                if (cmd.name() == null || cmd.name().isBlank())
                    return "Medicine name required.";
                if (cmd.price() == null || cmd.price() < 0)
                    return "Price must be >= 0.";
            }
            
            default -> {
                // UNKNOWN or unsupported
            }
        }

        return null; // valid
    }

    private boolean medicineExists(String name) {
        if (name == null) return false;
        String needle = name.trim().toLowerCase();
        return summaryService.getSummary().values().stream()
                .anyMatch(m -> m.name() != null && m.name().trim().toLowerCase().equals(needle));
    }

    public AdminInventoryCommandService(
            InventoryService inventoryService,
            InventorySummaryService summaryService,
            GeminiHttpService gemini
    ) {
        this.inventoryService = inventoryService;
        this.summaryService = summaryService;
        this.gemini = gemini;
    }

    private String cleanName(String raw) {
        String s = (raw == null) ? "" : raw.trim();
        s = s.replaceAll("[^a-zA-Z0-9 _\\-]", "");
        return s.isBlank() ? null : s;
    }    

    private String cleanJsonIfWrapped(String s) {
        if (s == null) return "";
        String t = s.trim();
    
        // remove ```json ... ``` wrappers
        if (t.startsWith("```")) {
            t = t.replaceAll("^```[a-zA-Z]*\\s*", "");
            t = t.replaceAll("\\s*```$", "");
        }
    
        // best effort: if Gemini adds extra text, grab first {...}
        int a = t.indexOf('{');
        int b = t.lastIndexOf('}');
        if (a >= 0 && b > a) {
            return t.substring(a, b + 1);
        }
        return t;
    }    

    public String handle(String input) {
        if (input == null || input.isBlank()) {
            return "What do you want to do?";
        }
    
        // 1) FAST PATH regex
        AdminCommand fast = tryRegexFastPath(input);
        if (fast != null) return execute(fast);
    
        // 2) Gemini attempt
        try {
            String json = gemini.classifyAdminCommand(input);
            json = cleanJsonIfWrapped(json);
            AdminCommand cmd = mapper.readValue(json, AdminCommand.class);
            return execute(cmd);
    
        } catch (Exception firstFail) {
    
            // 3) Repair attempt (force JSON)
            try {
                String repairPrompt =
                        "Return ONLY valid JSON matching schema " +
                        "{\"action\":\"...\",\"name\":\"...\",\"quantity\":123,\"expiry\":\"DD-MM-YYYY\",\"price\":12.34}. " +
                        "No extra text.\n\nInput:\n" + input;
    
                String fixed = gemini.generate(repairPrompt);
                fixed = cleanJsonIfWrapped(fixed);
    
                AdminCommand cmd = mapper.readValue(fixed, AdminCommand.class);
                return execute(cmd);
    
            } catch (Exception secondFail) {
                return "⚠️ I couldn’t parse that. Try: “add 10 panadol expiring 14-12-2027” or “remove 5 panadol”.";
            }
        }
    }    
    

    private String execute(AdminCommand cmd) {
        cmd = normalize(cmd);
    
        String err = validate(cmd);
        if (err != null) {
            return "⚠️ " + err;
        }
        String action = cmd.action();

        switch (action) {

            case "LIST" -> {
                Map<String, MedicineSummary> summary = summaryService.getSummary();
                if (summary.isEmpty()) return "Inventory is empty.";

                StringBuilder sb = new StringBuilder("In stock:\n");
                for (MedicineSummary m : summary.values()) {
                    sb.append("- ").append(m.name()).append(": ").append(m.totalStock()).append("\n");
                }
                return sb.toString().trim();
            }

            case "LOW_STOCK" -> {
                int threshold = (cmd.quantity() == null) ? 5 : cmd.quantity();
            
                var low = inventoryService.listLowStock(threshold);
                if (low.isEmpty()) return "✅ No low-stock medicines (≤ " + threshold + ").";
            
                StringBuilder sb = new StringBuilder("Low stock (≤ " + threshold + "):\n");
                for (var m : low) {
                    sb.append("- ").append(m.name()).append(": ").append(m.totalStock()).append("\n");
                }
                return sb.toString().trim();
            }                   

            case "ADD" -> {
                if (cmd.name() == null || cmd.name().isBlank()) {
                    return "Medicine name required.";
                }
                if (cmd.quantity() == null || cmd.quantity() <= 0) {
                    return "Quantity must be > 0.";
                }
                if (cmd.expiry() == null || cmd.expiry().isBlank()) {
                    return "Expiry required (DD-MM-YYYY).";
                }
                inventoryService.addStock(cmd.name(), cmd.quantity(), cmd.price(), cmd.expiry());
                int now = inventoryService.getCurrentStock(cmd.name());
                return "✅ Added " + cmd.quantity() + " " + cmd.name() + ". Now: " + now;
            }

            case "REMOVE" -> {
                if (cmd.name() == null || cmd.name().isBlank()) {
                    return "Medicine name required.";
                }
                if (cmd.quantity() == null || cmd.quantity() <= 0) {
                    return "Quantity must be > 0.";
                }
                if (!medicineExists(cmd.name())) return "Medicine not found: " + cmd.name();

                inventoryService.removeStock(cmd.name(), cmd.quantity());
                int now = inventoryService.getCurrentStock(cmd.name());
                return "✅ Removed " + cmd.quantity() + " " + cmd.name() + ". Now: " + now;
            }

            case "SET" -> {
                if (cmd.name() == null || cmd.name().isBlank()) {
                    return "Medicine name required.";
                }
                if (cmd.quantity() == null || cmd.quantity() < 0) {
                    return "Quantity must be 0 or more.";
                }
                if (!medicineExists(cmd.name())) return "Medicine not found: " + cmd.name();

                inventoryService.setStock(cmd.name(), cmd.quantity(), null, cmd.expiry());
                int now = inventoryService.getCurrentStock(cmd.name());
                return "✅ Set " + cmd.name() + " to " + now + ".";
            }

            case "UPDATE_PRICE" -> {
                if (cmd.name() == null || cmd.name().isBlank()) return "Medicine name required.";
                if (cmd.price() == null || cmd.price() < 0) return "Price must be >= 0.";
                if (!medicineExists(cmd.name())) return "Medicine not found: " + cmd.name();
                inventoryService.updatePrice(cmd.name(), cmd.price());
                return "✅ Updated price for " + cmd.name() + " to " + cmd.price();
            }

            case "UNKNOWN" -> { return help(); }

            default -> {
                return help();
            }
        }
    }

    private String help() {
        return "Try: add 10 panadol expiring 14-12-2027 / remove 5 panadol / set panadol to 30 / what's in stock / low stock";
    }

    private AdminCommand tryRegexFastPath(String input) {
        if (input == null) return null;
    
        // Add 10 Panadol
        var add = java.util.regex.Pattern
            .compile("^\\s*add\\s+(\\d+)\\s+(.+?)" +
                "(?:\\s+(?:price|\\$)\\s*(\\d+(?:\\.\\d{1,2})?))?" +
                "\\s*(?:expiring\\s+(\\d{2}-\\d{2}-\\d{4}))?\\s*$",
                java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(input);
    
        if (add.matches()) {
            int qty = Integer.parseInt(add.group(1));
            String name = cleanName(add.group(2));
            Double price = (add.group(3) == null) ? null : Double.parseDouble(add.group(3));
            String expiry = add.group(4); // may be null
            return new AdminCommand("ADD", name, qty, expiry, price);
        }
    
        // Remove 5 Panadol
        var remove = java.util.regex.Pattern
                .compile("^\\s*remove\\s+(\\d+)\\s+(.+?)\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(input);
    
        if (remove.matches()) {
            int qty = Integer.parseInt(remove.group(1));
            String name = cleanName(remove.group(2));
            return new AdminCommand("REMOVE", name, qty, null, null);
        }
    
        // Set 30 Panadol
        var set = java.util.regex.Pattern
                .compile("^\\s*set\\s+(\\d+)\\s+(.+?)\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(input);
    
        if (set.matches()) {
            int desired = Integer.parseInt(set.group(1));
            String name = cleanName(set.group(2));
            return new AdminCommand("SET", name, desired, null, null);
        }
    
        // what's in stock / list
        if (input.trim().equalsIgnoreCase("list") ||
            input.trim().equalsIgnoreCase("list inventory") ||
            input.trim().equalsIgnoreCase("what's in stock") ||
            input.trim().equalsIgnoreCase("whats in stock")) {
            return new AdminCommand("LIST", null, null, null, null);
        }
    
        // low stock 10 (or low stock)
        var low = java.util.regex.Pattern
                .compile("^\\s*(?:list\\s+)?low\\s+stock(?:\\s+(\\d+))?\\s*$", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(input);
    
        if (low.matches()) {
            Integer threshold = (low.group(1) == null) ? null : Integer.parseInt(low.group(1));
            // We'll store threshold in quantity field (simple reuse)
            return new AdminCommand("LOW_STOCK", null, threshold, null, null);
        }
    
        return null; // no regex match -> let Gemini handle it
    }
    
}
