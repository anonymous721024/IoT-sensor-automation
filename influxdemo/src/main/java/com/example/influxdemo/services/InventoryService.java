package com.example.influxdemo.services;

import com.example.influxdemo.data.InfluxClient;
import com.example.influxdemo.models.MedicineRecord;
import com.example.influxdemo.services.InventorySummaryService.MedicineSummary;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class InventoryService {

    private final InfluxClient influxClient;
    private final InventorySummaryService summaryService;
    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public InventoryService(InfluxClient influxClient, InventorySummaryService summaryService) {
        this.influxClient = influxClient;
        this.summaryService = summaryService;
    }

    // ---- helper ----
    private String normalizeOrThrowExpiry(String expiry) {
        if (expiry == null || expiry.isBlank()) return null;
        try {
            LocalDate.parse(expiry.trim(), DMY);
            return expiry.trim();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date. Use DD-MM-YYYY (e.g., 14-12-2027).");
        }
    }    

    // --------- Event-based operations ---------

    /** Add stock as an event (+delta). */
    public void addStock(String name, int qty, Double price, String expiry) {
        if (qty <= 0) throw new IllegalArgumentException("Qty must be > 0");
        String ex = normalizeOrThrowExpiry(expiry);
        influxClient.writeMedicineEvent(name, qty, price, ex);        
    }

    /** Remove stock as an event (-delta), with safety check. */
    public void removeStock(String name, int qty) {
        if (qty <= 0) throw new IllegalArgumentException("Qty must be > 0");

        int current = getCurrentStock(name);
        if (current <= 0) {
            throw new IllegalArgumentException("No stock available for " + name);
        }
        if (qty > current) {
            throw new IllegalArgumentException("Cannot remove " + qty + " because only " + current + " in stock.");
        }

        // store as negative event
        influxClient.writeMedicineEvent(name, -qty, null, null);
    }

    /**
     * SET stock = convert to delta event (desired - current).
     * This preserves event history and keeps your summaries correct.
     */
    public void setStock(String name, int desiredStock, Double price, String expiry) {
        if (desiredStock < 0) throw new IllegalArgumentException("Stock cannot be negative");

        int current = getCurrentStock(name);
        int delta = desiredStock - current;

        // If no change, do nothing
        if (delta == 0) return;

        String ex = normalizeOrThrowExpiry(expiry);
        influxClient.writeMedicineEvent(name, delta, price, ex);        
    }

    /** Current computed stock from summary (deterministic). */
    public int getCurrentStock(String name) {
        if (name == null) return 0;
    
        String needle = name.trim().toLowerCase();
    
        Map<String, MedicineSummary> summary = summaryService.getSummary();
        for (MedicineSummary s : summary.values()) {
            if (s.name() != null && s.name().trim().toLowerCase().equals(needle)) {
                return s.totalStock();
            }
        }
        return 0;
    }

    /** Low stock list, sorted lowest first. */
    public List<MedicineSummary> listLowStock(int threshold) {
        return summaryService.getSummary().values().stream()
                .filter(m -> m.totalStock() <= threshold)
                .sorted(Comparator.comparingInt(MedicineSummary::totalStock))
                .toList();
    }

    public List<MedicineRecord> searchMedicineRows(String nameContains, int limit) {
    return influxClient.queryMedicineRows(nameContains, limit);
    }

    public void updatePrice(String name, double price) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Medicine name required.");
        if (price < 0) throw new IllegalArgumentException("Price must be >= 0.");
        influxClient.writeMedicineEvent(name, 0, price, null);
    }    
}
