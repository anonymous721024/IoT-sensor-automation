package influxmcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Map;
import java.lang.reflect.Constructor;

public class App {
    private static final Logger log = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) throws Exception {
        McpJsonMapper jsonMapper = createJacksonMcpJsonMapper();

        // StdioServerTransportProvider(McpJsonMapper)
        StdioServerTransportProvider transportProvider =
                new StdioServerTransportProvider(jsonMapper);

        // Read Influx settings from environment (safe defaults)
        String influxUrl = env("INFLUX_URL", "http://localhost:8181");
        String influxToken = env("INFLUX_TOKEN", "");
        String influxDb = env("INFLUX_DB", "pharmacy");

        InfluxSqlClient influx = new InfluxSqlClient(influxUrl, influxToken, influxDb);

        // Define input schema (as JSON) for the tool.
        // NOTE: In MCP Java 0.17.1 you can pass schema via builder.inputSchema(jsonMapper, jsonString)
        String listMedicinesSchema = """
        {
        "type": "object",
        "properties": {
            "limit": { "type": "integer", "description": "Max rows to return", "default": 20 }
        }
        }
        """;

        // Build the Tool using the builder (correct for 0.17.1)
        McpSchema.Tool listMedicinesTool = McpSchema.Tool.builder()
                .name("list_medicines")
                .title("List medicines")
                .description("List latest medicines from InfluxDB (SQL over HTTP).")
                .inputSchema(jsonMapper, listMedicinesSchema)
                .build();

                // Register the tool using the NON-deprecated API: .tool(...)
                var server = McpServer.sync(transportProvider)
                .serverInfo("influx-mcp-server", "0.0.1")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .tool(listMedicinesTool, (exchange, arguments) -> {
                    int limit = 20;

                    if (arguments != null && arguments.get("limit") != null) {
                        try {
                            limit = Integer.parseInt(arguments.get("limit").toString());
                        } catch (Exception ignored) {
                            // keep default
                        }
                    }

                    String sql =
                            "SELECT time, name, stock, price, expiry " +
                            "FROM iox.medicine " +
                            "ORDER BY time DESC " +
                            "LIMIT " + limit;

                    String json = influx.querySqlRawJson(sql);

                    // 0.17.1 supports returning plain string directly
                    return new McpSchema.CallToolResult(json, false);
                })
                .build();


        log.info("MCP server running (STDIO). Waiting for a client...");
        Thread.currentThread().join();
    }

    private static McpJsonMapper createJacksonMcpJsonMapper() throws Exception {
        ObjectMapper om = new ObjectMapper();

        // Try common class names across MCP Java variants
        String[] candidates = {
                "io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper",
                "io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper",
                "io.modelcontextprotocol.jackson2.JacksonMcpJsonMapper",
                "io.modelcontextprotocol.jackson.JacksonMcpJsonMapper"
        };

        for (String cn : candidates) {
            try {
                Class<?> cls = Class.forName(cn);

                // Prefer ctor(ObjectMapper)
                for (Constructor<?> c : cls.getConstructors()) {
                    Class<?>[] p = c.getParameterTypes();
                    if (p.length == 1 && p[0].getName().equals("com.fasterxml.jackson.databind.ObjectMapper")) {
                        Object obj = c.newInstance(om);
                        return (McpJsonMapper) obj;
                    }
                }

                // Fallback: no-arg ctor
                for (Constructor<?> c : cls.getConstructors()) {
                    if (c.getParameterCount() == 0) {
                        Object obj = c.newInstance();
                        return (McpJsonMapper) obj;
                    }
                }
            } catch (ClassNotFoundException ignored) {
                // try next candidate
            }
        }

        throw new IllegalStateException(
                "Could not find a Jackson MCP JsonMapper class. " +
                "mcp-json-jackson2 is in pom, but mapper class name did not match known candidates."
        );
    }
    private static String env(String key, String defaultVal) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? defaultVal : v;
    }
}
