
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

/**
 *
 * @author Ulikhaya Mazibuko
 */
public class DeliverHandler implements HttpHandler {

    private final SheetsClient   sheets;
    private final WhatsAppClient whatsApp;

    public DeliverHandler(Config config) {
        this.sheets   = new SheetsClient(config);
        this.whatsApp = new WhatsAppClient(config);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Handle CORS preflight
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            respond(exchange, 204, "");
            return;
        }

        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method Not Allowed");
            return;
        }

        // Read request body
        String body = readBody(exchange);

        // Extract fields from JSON body
        String orderId    = Json.getString(body, "orderId");
        String customerId = Json.getString(body, "customerId");
        String firstName  = Json.getString(body, "firstName");
        String whatsapp   = Json.getString(body, "whatsapp");

        if (orderId == null || customerId == null) {
            respond(exchange, 400, "{\"error\":\"Missing orderId or customerId\"}");
            return;
        }

        System.out.println("[Deliver] Marking delivered: " + orderId);

        // Mark delivered in Sheet
        boolean success = sheets.markDelivered(orderId, customerId);

        if (!success) {
            respond(exchange, 500, "{\"error\":\"Failed to update Sheet\"}");
            return;
        }

        // Fire post-delivery WhatsApp if we have the customer's number
        if (whatsapp != null && !whatsapp.isBlank() &&
            firstName != null && !firstName.isBlank()) {
            whatsApp.sendPostDelivery(whatsapp, firstName);
        }

        respond(exchange, 200, "{\"success\":true,\"orderId\":\"" + orderId + "\"}");
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void respond(HttpExchange exchange, int status,
                         String body) throws IOException {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}