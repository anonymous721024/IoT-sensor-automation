package com.example.influxdemo.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.example.influxdemo.models.MedicineRecord;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;

/**
 * MCP client responsible for:
 * - launching the MCP server (stdio)
 * - calling MCP tools
 *
 * This is STEP 1: raw JSON passthrough (no parsing yet).
 */
@Component
public class McpPharmacyClient {

    private McpSyncClient client;
    
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${mcp.command}")
    private String command;

    @Value("${mcp.args}")
    private String argsCsv;

    /**
     * Starts the MCP server and initializes the client
     * when Spring Boot finishes starting.
     */
    @PostConstruct
    public void start() {
        List<String> args = Arrays.stream(argsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        ServerParameters params = ServerParameters.builder(command)
                .args(args.toArray(new String[0]))
                .build();

        McpJsonMapper jsonMapper = McpJsonMapper.createDefault();

        StdioClientTransport transport =
                new StdioClientTransport(params, jsonMapper);

        client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(10))
                .build();

        client.initialize();

        System.out.println("[MCP] Client initialized");
    }
 

    /**
     * Calls MCP tool: list_medicines
     * Returns RAW JSON string for now.
     */
    public String listMedicinesRaw(int limit) {
        if (client == null) {
            return "[]";
        }

        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest(
                        "list_medicines",
                        Map.of("limit", limit)
                )
        );

        if (result == null || result.content() == null || result.content().isEmpty()) {
            return "[]";
        }

        // MCP returns a list of content objects — we want the text
        var content = result.content().get(0);

        if (content instanceof McpSchema.TextContent text) {
            return text.text();
        }

        return "[]";
    }

    public List<MedicineRecord> listMedicines(int limit) {
        String json = listMedicinesRaw(limit);

        if (json == null || json.isBlank() || json.equals("[]")) {
            return new ArrayList<>();
        }

        try {
            return mapper.readValue(json, new TypeReference<List<MedicineRecord>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Gracefully shut down MCP process on Spring shutdown.
     */
    @PreDestroy
    public void stop() {
        if (client != null) {
            client.closeGracefully();
        }
    }
}