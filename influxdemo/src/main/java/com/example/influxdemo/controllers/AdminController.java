package com.example.influxdemo.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.ui.Model;

import com.example.influxdemo.data.InfluxClient;
import com.example.influxdemo.models.SensorRegistryRow;
import com.example.influxdemo.models.SensorReadingEventRow;
import com.example.influxdemo.models.AppSettingsRow;

import java.util.List;
import java.util.Collections;

@Controller
public class AdminController {

    private final InfluxClient influx;

    public AdminController(InfluxClient influx) {
        this.influx = influx;
    }

    // --------------------
    // Admin root
    // --------------------
    @GetMapping("/admin")
    public String adminForm() {
        return "redirect:/admin/sensors";
    }

    // --------------------
    // Sensors (MASTER DATA)
    // --------------------
    @GetMapping("/admin/sensors")
    public String adminSensorsPage(
            @RequestParam(required = false) String q,
            Model model
    ) {
        String query = (q == null) ? "" : q.trim();

        List<SensorRegistryRow> sensors =
                influx.querySensorRegistry(query, 300);

        List<SensorReadingEventRow> events =
                influx.querySensorEvents(300);

        AppSettingsRow settings =
                influx.queryLatestSettings();

        model.addAttribute("active", "sensors");
        model.addAttribute("q", query);
        model.addAttribute("sensors", sensors);
        model.addAttribute("events", events);
        model.addAttribute("settings", settings);

        return "admin-sensors";
    }

    @PostMapping("/admin/sensors/create")
    public String createSensor(
            @RequestParam String sensorName,
            @RequestParam String deviceName,
            @RequestParam String building,
            @RequestParam String levelArea,
            @RequestParam String ambientArea,
            @RequestParam(required = false) String sensorDescription,
            @RequestParam(required = false) String q
    ) {
        influx.writeSensorRegistry(
                sensorName,
                deviceName,
                building,
                levelArea,
                ambientArea,
                sensorDescription == null ? "" : sensorDescription
        );

        return "redirect:/admin/sensors?q=" + (q == null ? "" : q);
    }

    @PostMapping("/admin/settings/update")
    public String updateSettings(
            @RequestParam double tempLow,
            @RequestParam double tempHigh,
            @RequestParam int overloadPercent
    ) {
        influx.writeSettings(tempLow, tempHigh, overloadPercent);
        return "redirect:/admin/sensors";
    }

    // --------------------
    // DATA FLOW (TIME SERIES)
    // --------------------
    @GetMapping("/admin/data-flow")
    public String dataFlow(
            @RequestParam(required = false) String building,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String sensor,
            Model model
    ) {
        // Dropdown values
        List<String> buildings = influx.queryDistinctBuildings();
        List<String> levels = influx.queryDistinctLevels();
        List<String> areas = influx.queryAmbientAreas();

        // Sensor master data filtered
        List<SensorRegistryRow> sensorsInScope =
                influx.querySensorRegistryFiltered(
                        building,
                        level,
                        area,
                        sensor,
                        1000
                );

        // Time series only when area selected
        List<SensorReadingEventRow> events = Collections.emptyList();
        if (area != null && !area.isBlank()) {
            int limit = Math.min(5000, Math.max(300, sensorsInScope.size() * 80));
            events = influx.queryRecentEventsByAmbientArea(area, limit);
        }

        AppSettingsRow settings = influx.queryLatestSettings();

        model.addAttribute("active", "dataflow");

        model.addAttribute("buildings", buildings);
        model.addAttribute("levels", levels);
        model.addAttribute("areas", areas);

        model.addAttribute("selectedBuilding", building == null ? "" : building);
        model.addAttribute("selectedLevel", level == null ? "" : level);
        model.addAttribute("selectedArea", area == null ? "" : area);
        model.addAttribute("sensorQuery", sensor == null ? "" : sensor);

        model.addAttribute("sensorsInScope", sensorsInScope);
        model.addAttribute("events", events);
        model.addAttribute("settings", settings);

        return "admin-data-flow";
    }

    // --------------------
    // DUMMY DATA GENERATOR
    // --------------------
    @PostMapping("/admin/data-flow/generate")
    public String generateDataFlow(
            @RequestParam String area,
            @RequestParam(required = false, defaultValue = "normal") String mode
    ) {
        if (area == null || area.isBlank()) {
            return "redirect:/admin/data-flow";
        }

        List<SensorRegistryRow> sensors =
                influx.querySensorsByAmbientArea(area, 500);

        if (sensors.isEmpty()) {
            return "redirect:/admin/data-flow?area=" + area;
        }

        AppSettingsRow settings = influx.queryLatestSettings();

        double tempLow = settings != null && settings.temp_low != null ? settings.temp_low : 15.0;
        double tempHigh = settings != null && settings.temp_high != null ? settings.temp_high : 27.0;
        int overloadPercent = settings != null && settings.overload_percent != null ? settings.overload_percent : 70;

        int total = sensors.size();
        int required = (int) Math.ceil((overloadPercent / 100.0) * total);

        int overloadCount;
        if ("force_alert".equalsIgnoreCase(mode)) {
            overloadCount = Math.max(required, 1);
        } else if ("force_ok".equalsIgnoreCase(mode)) {
            overloadCount = Math.max(0, required - 1);
        } else {
            overloadCount = Math.min(1, total);
        }

        long now = System.currentTimeMillis();

        for (int minute = 59; minute >= 0; minute--) {
            long ts = now - (minute * 60_000L);

            for (int i = 0; i < sensors.size(); i++) {
                boolean overloaded = i < overloadCount;

                double temp;
                String status;

                if (overloaded) {
                    temp = tempHigh + 2 + Math.random() * 2;
                    status = "OVER";
                } else {
                    temp = (tempLow + tempHigh) / 2 + (Math.random() - 0.5) * 2;
                    status = "OK";
                }

                influx.writeSensorReadingEventAt(
                        sensors.get(i).sensor_name,
                        area,
                        temp,
                        status,
                        ts
                );
            }
        }

        return "redirect:/admin/data-flow?area=" + area;
    }
}
