package com.dailyloaf.handlers;

import com.dailyloaf.sheets.SheetsClient;
import com.dailyloaf.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import com.dailyloaf.config.Config;

public class WaitlistHandler implements HttpHandler {

    private final SheetsClient sheetsClient;

    public WaitlistHandler(Config config) {
        this.sheetsClient = new SheetsClient(config);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        setCorsHeaders(exchange);

        // Handle browser CORS preflight
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"error\":\"Method not allowed\"}");
            return;
        }

        try {
            String body = new String(
                exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
            );

            String name     = Json.getString(body, "name");
            String whatsapp = Json.getString(body, "whatsapp");
            String source   = Json.getString(body, "source");

            // Basic validation
            if (name == null || name.isBlank()) {
                sendJson(exchange, 400, "{\"error\":\"Name is required\"}");
                return;
            }
            if (whatsapp == null || whatsapp.isBlank()) {
                sendJson(exchange, 400, "{\"error\":\"WhatsApp number is required\"}");
                return;
            }

            String normalized = normalizePhone(whatsapp.trim());
            if (normalized == null) {
                sendJson(exchange, 400, "{\"error\":\"Invalid South African phone number\"}");
                return;
            }

            String timestamp = ZonedDateTime
                .now(ZoneId.of("Africa/Johannesburg"))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            sheetsClient.addToWaitlist(
                timestamp,
                name.trim(),
                normalized,
                source != null ? source : "landing_page",
                "NEW"
            );

            System.out.println("Waitlist: " + name.trim() + " | " + normalized);
            sendJson(exchange, 200, "{\"success\":true}");

        } catch (Exception e) {
            System.err.println("WaitlistHandler error: " + e.getMessage());
            sendJson(exchange, 500, "{\"error\":\"Server error\"}");
        }
    }

    private String normalizePhone(String raw) {
        String v = raw.replace(" ", "").replace("-", "");
        if (v.startsWith("+27") && v.length() == 12) return v;
        if (v.startsWith("27")  && v.length() == 11) return "+" + v;
        if (v.startsWith("0")   && v.length() == 10) return "+27" + v.substring(1);
        return null;
    }

    private void setCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin",  "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private void sendJson(HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}