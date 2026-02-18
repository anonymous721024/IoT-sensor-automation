package com.example.influxdemo.services;

import com.example.influxdemo.data.InfluxClient;
import com.example.influxdemo.models.MedicineRecord;
import org.springframework.stereotype.Service;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class InventorySummaryService {

    private final InfluxClient influx;

    private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public InventorySummaryService(InfluxClient influx) {
        this.influx = influx;
    }

    public Map<String, MedicineSummary> getSummary() {
        // Read events (stock_delta) from medicine_event
        List<MedicineRecord> events = influx.queryMedicineEvents(2000);

        Map<String, List<MedicineRecord>> grouped =
                events.stream()
                        .filter(r -> r.getName() != null && !r.getName().isBlank())
                        .collect(Collectors.groupingBy(MedicineRecord::getName));

        Map<String, MedicineSummary> result = new LinkedHashMap<>();

        for (var entry : grouped.entrySet()) {
            String name = entry.getKey();
            List<MedicineRecord> rows = entry.getValue();

            int totalStock = rows.stream().mapToInt(MedicineRecord::getStock).sum();

            LocalDate earliestExpiry = rows.stream()
                .map(MedicineRecord::getExpiry)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> {
                        try { return LocalDate.parse(s, DMY); }
                        catch (DateTimeParseException e) { return null; }
                })
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);

            // Price is optional in events; ignore zeros (which are usually "missing")
            DoubleSummaryStatistics priceStats = rows.stream()
                    .map(MedicineRecord::getPrice)
                    .filter(Objects::nonNull)
                    .mapToDouble(Double::doubleValue)
                    .filter(p -> p > 0)
                    .summaryStatistics();

            double minPrice = priceStats.getCount() == 0 ? 0 : priceStats.getMin();
            double maxPrice = priceStats.getCount() == 0 ? 0 : priceStats.getMax();

            result.put(name, new MedicineSummary(name, totalStock, earliestExpiry, minPrice, maxPrice));
        }

        return result;
    }

    public record MedicineSummary(
            String name,
            int totalStock,
            LocalDate earliestExpiry,
            double minPrice,
            double maxPrice
    ) {}
}
