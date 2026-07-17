
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

public class NotDeliveredHandler implements HttpHandler {

    private final SheetsClient   sheets;
    private final WhatsAppClient whatsApp;
    private final Config         config;

    public NotDeliveredHandler(Config config) {
        this.config   = config;
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

        String body      = readBody(exchange);
        String orderId   = Json.getString(body, "orderId");
        String firstName = Json.getString(body, "firstName");
        String whatsapp  = Json.getString(body, "whatsapp");
        String reason    = Json.getString(body, "reason");

        if (orderId == null || reason == null) {
            respond(exchange, 400,
                "{\"error\":\"Missing orderId or reason\"}");
            return;
        }

        System.out.println("[NotDelivered] " + orderId +
                           " | Reason: " + reason);

        // Mark in Sheet
        boolean success = sheets.markNotDelivered(orderId, reason);

        if (!success) {
            respond(exchange, 500,
                "{\"error\":\"Failed to update Sheet\"}");
            return;
        }

        // Send "we were nearby" WhatsApp
        if (whatsapp != null && !whatsapp.isBlank() &&
            firstName != null && !firstName.isBlank()) {
            sendNotDeliveredMessage(whatsapp, firstName, reason);
        }

        respond(exchange, 200,
            "{\"success\":true,\"orderId\":\"" + orderId + "\"}");
    }

    /**
     * Sends the appropriate WhatsApp message based on the reason.
     * Each reason gets a slightly different message.
     */
    private void sendNotDeliveredMessage(String to, String firstName,
                                         String reason) {
        String message = switch (reason.toLowerCase()) {
            case "no answer" -> String.format(
                "Hi %s, we stopped by but couldn't reach you. " +
                "Contact us on %s to arrange redelivery.",
                firstName, config.getBusinessPhoneNumber()
            );
            case "wrong address" -> String.format(
                "Hi %s, we couldn't locate your address today. " +
                "Please reply with clearer directions so we can " +
                "find you next time.",
                firstName
            );
            case "cancelled" -> String.format(
                "Hi %s, your order has been cancelled as requested. " +
                "Message us anytime to place a new order.",
                firstName
            );
            default -> String.format(
                "Hi %s, we were unable to complete your delivery today. " +
                "Contact us on %s and we'll sort it out.",
                firstName, config.getBusinessPhoneNumber()
            );
        };

        whatsApp.send(to, message);
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
