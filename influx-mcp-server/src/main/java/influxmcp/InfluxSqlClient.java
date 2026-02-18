package influxmcp;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Minimal HTTP client for InfluxDB 3 SQL endpoint (/api/v3/query_sql).
 * MCP tools will call this to fetch data.
 */
public class InfluxSqlClient {

    private final String influxUrl;
    private final String token;
    private final String db;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper om = new ObjectMapper();

    public InfluxSqlClient(String influxUrl, String token, String db) {
        // Sanitize inputs (fixes Bad authority caused by spaces/newlines/quotes)
        this.influxUrl = sanitizeBaseUrl(influxUrl);
        this.token = (token == null) ? "" : token.trim();
        this.db = (db == null) ? "" : db.trim();

        System.err.println("[MCP] INFLUX_URL='" + this.influxUrl + "'");
        System.err.println("[MCP] INFLUX_DB='" + this.db + "'");
        System.err.println("[MCP] INFLUX_TOKEN_LEN=" + this.token.length());
    }

    private static String sanitizeBaseUrl(String url) {
        if (url == null) return "";
        String u = url.trim();

        // remove accidental surrounding quotes
        if ((u.startsWith("\"") && u.endsWith("\"")) || (u.startsWith("'") && u.endsWith("'"))) {
            u = u.substring(1, u.length() - 1).trim();
        }

        // remove trailing slash to avoid double slashes later
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }

        // if someone passed localhost:8181, add scheme
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "http://" + u;
        }

        return u;
    }


    public String querySqlRawJson(String sql) {
        try {
            String endpoint = this.influxUrl + "/api/v3/query_sql";

            String body = om.createObjectNode()
                    .put("db", db)
                    .put("q", sql)
                    .toString();

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());

            // Return [] instead of throwing, so MCP never crashes the client
            if (resp.statusCode() >= 400) {
                // Return the REAL error so we can debug (don’t hide it as [])
                String bodyText = (resp.body() == null) ? "" : resp.body();
                return "{\"mcp_error\":true,\"status\":" + resp.statusCode() +
                        ",\"body\":" + om.writeValueAsString(bodyText) + "}";
            }
            return (resp.body() == null || resp.body().isBlank()) ? "[]" : resp.body();

        } catch (Exception e) {
            return "[]";
        }
    }
}
