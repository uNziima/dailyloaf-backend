package com.dailyloaf.handlers;

import com.dailyloaf.config.Config;
import com.dailyloaf.sheets.SheetsClient;
import com.dailyloaf.util.Json;
import com.dailyloaf.whatsapp.WhatsAppClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class PaymentHandler implements HttpHandler {

    private final SheetsClient   sheets;
    private final WhatsAppClient whatsApp;

    public PaymentHandler(Config config) {
        this.sheets   = new SheetsClient(config);
        this.whatsApp = new WhatsAppClient(config);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            respond(exchange, 204, "");
            return;
        }

        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method Not Allowed");
            return;
        }

        String body       = readBody(exchange);
        String orderId    = Json.getString(body, "orderId");
        String customerId = Json.getString(body, "customerId");
        String firstName  = Json.getString(body, "firstName");
        String whatsapp   = Json.getString(body, "whatsapp");
        String deliveryDay = Json.getString(body, "deliveryDay");

        if (orderId == null || customerId == null) {
            respond(exchange, 400, "{\"error\":\"Missing orderId or customerId\"}");
            return;
        }

        System.out.println("[Payment] Confirming payment for: " + orderId);

        // Update order status to PAID
        sheets.updateOrderStatus(orderId,
            com.dailyloaf.model.OrderStatus.PAID);

        System.out.println("[Payment] Order marked PAID: " + orderId);

        // Send payment confirmed WhatsApp
        if (whatsapp != null && !whatsapp.isBlank() &&
            firstName != null && !firstName.isBlank()) {
            whatsApp.sendPaymentConfirmed(whatsapp, firstName,
                deliveryDay != null ? deliveryDay : "delivery");
        }

        respond(exchange, 200,
            "{\"success\":true,\"orderId\":\"" + orderId + "\"}");
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void respond(HttpExchange exchange, int status,
                         String body) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods",
            "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers",
            "Content-Type");
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}