package com.example.influxdemo.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import com.example.influxdemo.models.SensorRegistryRow;
import com.example.influxdemo.models.SensorReadingEventRow;
import com.example.influxdemo.models.AppSettingsRow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

@Component
public class InfluxClient {

    @Value("${influx.url}")
    private String influxUrl;

    @Value("${influx.token}")
    private String token;

    @Value("${influx.db}")
    private String db;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    // Optional health check
    public String health() {
        String url = influxUrl + "/health";
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);

        HttpEntity<Void> entity = new HttpEntity<>(headers);
        return restTemplate.exchange(url, HttpMethod.GET, entity, String.class).getBody();
    }

    public void writeSensorRegistry(
            String sensorName,
            String deviceName,
            String building,
            String levelArea,
            String ambientArea,
            String sensorDescription
    ) {
        // store all as fields for simplicity (easy querying)
        String line =
                "sensor_registry " +
                "sensor_name=" + quoteString(sensorName) + "," +
                "device_name=" + quoteString(deviceName) + "," +
                "building=" + quoteString(building) + "," +
                "level_area=" + quoteString(levelArea) + "," +
                "ambient_area=" + quoteString(ambientArea) + "," +
                "sensor_description=" + quoteString(sensorDescription);

        postLineProtocol(line);
    }

    public void writeSettings(double tempLow, double tempHigh, int overloadPercent) {
        String line =
                "app_settings " +
                "temp_low=" + tempLow + "," +
                "temp_high=" + tempHigh + "," +
                "overload_percent=" + overloadPercent + "i";

        postLineProtocol(line);
    }

    public void writeSensorReadingEventAt(
            String sensorName,
            String ambientArea,
            double temperature,
            String status,
            long epochMillis
    ) {
        long tsNs = epochMillis * 1_000_000L;

        String line =
                "sensor_reading_event " +
                "sensor_name=" + quoteString(sensorName) + "," +
                "ambient_area=" + quoteString(ambientArea) + "," +
                "temperature=" + temperature + "," +
                "status=" + quoteString(status) +
                " " + tsNs;

        postLineProtocol(line);
    }

    // --- Internal: Execute query_sql and return raw JSON ---
    // --- Internal: Execute query_sql and return raw JSON ---
    private String querySqlRaw(String sql) {
        String url = influxUrl + "/api/v3/query_sql";

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            // Build JSON properly (escapes \n, \r, etc.)
            String body = mapper.createObjectNode()
                    .put("db", db)
                    .put("q", sql)
                    .toString();

            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            return (resp.getBody() == null || resp.getBody().isBlank()) ? "[]" : resp.getBody();

        } catch (Exception e) {
            // Return a JSON error so you can debug without crashing the app
            return "{\"mcp_error\":true,\"message\":\"querySqlRaw failed\",\"detail\":\""
                    + e.getClass().getSimpleName() + ": " + (e.getMessage() == null ? "" : e.getMessage().replace("\"","'"))
                    + "\"}";
        }
    }

    private boolean tableExistsInIox(String tableName) {
        String safe = tableName.replace("'", "''");
        String sql =
                "SELECT table_name " +
                "FROM information_schema.tables " +
                "WHERE table_schema = 'iox' AND table_name = '" + safe + "' " +
                "LIMIT 1";
    
        String json = querySqlRaw(sql);
    
        // Works with both response shapes you already handle:
        // - [] or [{"table_name":"..."}]
        return json != null && json.contains("\"table_name\":\"" + tableName + "\"");
    }    

    // Line-protocol helpers
    private String quoteString(String s) {
        if (s == null) return "\"\"";
        String cleaned = s.replace("\"", "\\\"");
        return "\"" + cleaned + "\"";
    }
    
    private <T> List<T> parseListFromJson(String json, TypeReference<List<T>> typeRef) {
        try {
            JsonNode root = mapper.readTree(json);
    
            if (root.isArray()) {
                return mapper.convertValue(root, typeRef);
            }
    
            JsonNode dataNode = root.get("data");
            if (dataNode != null && dataNode.isArray()) {
                return mapper.convertValue(dataNode, typeRef);
            }
    
            return new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public List<SensorRegistryRow> querySensorRegistry(String nameOrAreaContains, int limit) {
        String where = "";
        if (nameOrAreaContains != null && !nameOrAreaContains.isBlank()) {
            String safe = nameOrAreaContains.replace("'", "''").toLowerCase();
            where = "WHERE lower(sensor_name) LIKE '%" + safe + "%' " +
                    "OR lower(ambient_area) LIKE '%" + safe + "%' ";
        }
    
        String sql =
                "SELECT time, sensor_name, device_name, building, level_area, ambient_area, sensor_description " +
                "FROM sensor_registry " +
                where +
                "ORDER BY time DESC " +
                "LIMIT " + limit;
    
        String json = querySqlRaw(sql);
        return parseListFromJson(json, new TypeReference<List<SensorRegistryRow>>() {});
    }
    
    public List<SensorRegistryRow> querySensorsByAmbientArea(String ambientArea, int limit) {
        if (ambientArea == null || ambientArea.isBlank()) return new ArrayList<>();
    
        String safe = ambientArea.replace("'", "''");
    
        String sql =
                "SELECT time, sensor_name, device_name, building, level_area, ambient_area, sensor_description " +
                "FROM sensor_registry " +
                "WHERE ambient_area = '" + safe + "' " +
                "ORDER BY time DESC " +
                "LIMIT " + limit;
    
        String json = querySqlRaw(sql);
        return parseListFromJson(json, new TypeReference<List<SensorRegistryRow>>() {});
    }

    public List<String> queryAmbientAreas() {
        String sql =
                "SELECT DISTINCT ambient_area " +
                "FROM sensor_registry " +
                "ORDER BY ambient_area ASC";
    
        String json = querySqlRaw(sql);
    
        List<java.util.Map<String, Object>> rows =
                parseListFromJson(json, new TypeReference<List<java.util.Map<String, Object>>>() {});
    
        List<String> areas = new ArrayList<>();
        for (var r : rows) {
            Object v = r.get("ambient_area");
            if (v != null) areas.add(v.toString());
        }
        return areas;
    }

    public List<SensorReadingEventRow> queryRecentEventsByAmbientArea(String ambientArea, int limit) {
        if (ambientArea == null || ambientArea.isBlank()) return new ArrayList<>();
    
        // Avoid 500 if no table yet
        // (optional – but recommended)
        // if (!tableExistsInIox("sensor_reading_event")) return new ArrayList<>();
    
        String safe = ambientArea.replace("'", "''");
    
        String sql =
                "SELECT time, sensor_name, ambient_area, temperature, status " +
                "FROM sensor_reading_event " +
                "WHERE ambient_area = '" + safe + "' " +
                "ORDER BY time DESC " +
                "LIMIT " + limit;
    
        String json = querySqlRaw(sql);
        List<SensorReadingEventRow> rows =
                parseListFromJson(json, new TypeReference<List<SensorReadingEventRow>>() {});
    
        // reverse to ASC for chart
        java.util.Collections.reverse(rows);
        return rows;
    }    

    public List<SensorReadingEventRow> querySensorEvents(int limit) {

        // Avoid 500 spam when the table hasn't been created yet
        if (!tableExistsInIox("sensor_reading_event")) {
            return new ArrayList<>();
        }
    
        String sql =
                "SELECT time, sensor_name, ambient_area, temperature, status " +
                "FROM sensor_reading_event " +
                "ORDER BY time DESC " +
                "LIMIT " + limit;
    
        String json = querySqlRaw(sql);
        return parseListFromJson(json, new TypeReference<List<SensorReadingEventRow>>() {});
    }
      
    
    public AppSettingsRow queryLatestSettings() {
        String sql =
                "SELECT time, temp_low, temp_high, overload_percent " +
                "FROM app_settings " +
                "ORDER BY time DESC " +
                "LIMIT 1";
    
        List<AppSettingsRow> rows = parseListFromJson(querySqlRaw(sql), new TypeReference<List<AppSettingsRow>>() {});
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void postLineProtocol(String line) {
        String url = influxUrl + "/api/v3/write_lp?db=" + db;
    
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + token);
        headers.setContentType(MediaType.TEXT_PLAIN);
    
        HttpEntity<String> entity = new HttpEntity<>(line, headers);
        ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
    
        if (resp.getStatusCode().isError()) {
            throw new RuntimeException("Influx write failed: " + resp.getStatusCode());
        }
    }    
    
    public List<SensorRegistryRow> querySensorRegistryFiltered(
            String building,
            String levelArea,
            String ambientArea,
            String sensorNameContains,
            int limit
    ) {
        List<String> clauses = new ArrayList<>();

        if (building != null && !building.isBlank()) {
            clauses.add("building = '" + building.replace("'", "''") + "'");
        }
        if (levelArea != null && !levelArea.isBlank()) {
            clauses.add("level_area = '" + levelArea.replace("'", "''") + "'");
        }
        if (ambientArea != null && !ambientArea.isBlank()) {
            clauses.add("ambient_area = '" + ambientArea.replace("'", "''") + "'");
        }
        if (sensorNameContains != null && !sensorNameContains.isBlank()) {
            String safe = sensorNameContains.replace("'", "''").toLowerCase();
            clauses.add("lower(sensor_name) LIKE '%" + safe + "%'");
        }

        String where = clauses.isEmpty() ? "" : ("WHERE " + String.join(" AND ", clauses) + " ");

        String sql =
                "SELECT time, sensor_name, device_name, building, level_area, ambient_area, sensor_description " +
                "FROM sensor_registry " +
                where +
                "ORDER BY time DESC " +
                "LIMIT " + limit;

        String json = querySqlRaw(sql);
        return parseListFromJson(json, new TypeReference<List<SensorRegistryRow>>() {});
    }

    public List<String> queryDistinctBuildings() {
        String sql = "SELECT DISTINCT building FROM sensor_registry ORDER BY building ASC";
        String json = querySqlRaw(sql);
        List<java.util.Map<String, Object>> rows =
                parseListFromJson(json, new TypeReference<List<java.util.Map<String, Object>>>() {});
        List<String> out = new ArrayList<>();
        for (var r : rows) {
            Object v = r.get("building");
            if (v != null) out.add(v.toString());
        }
        return out;
    }
    
    public List<String> queryDistinctLevels() {
        String sql = "SELECT DISTINCT level_area FROM sensor_registry ORDER BY level_area ASC";
        String json = querySqlRaw(sql);
        List<java.util.Map<String, Object>> rows =
                parseListFromJson(json, new TypeReference<List<java.util.Map<String, Object>>>() {});
        List<String> out = new ArrayList<>();
        for (var r : rows) {
            Object v = r.get("level_area");
            if (v != null) out.add(v.toString());
        }
        return out;
    }
}
