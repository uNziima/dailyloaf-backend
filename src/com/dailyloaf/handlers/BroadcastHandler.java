
package com.dailyloaf.handlers;

import com.dailyloaf.config.Config;
import com.dailyloaf.sheets.SheetsClient;
import com.dailyloaf.whatsapp.WhatsAppClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Ulikhaya Mazibuko
 */

public class BroadcastHandler implements HttpHandler {

    private final SheetsClient   sheets;
    private final WhatsAppClient whatsApp;

    public BroadcastHandler(Config config) {
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

        String day = queryParam(exchange.getRequestURI(), "day");
        if (day == null || day.isBlank()) {
            respond(exchange, 400,
                "{\"error\":\"Missing day parameter\"}");
            return;
        }

        System.out.println("[Broadcast] Sending on-the-way messages for: "
                           + day);

        List<Map<String, String>> stops =
            sheets.getDeliveriesForDay(day);

        int sent = 0;
        for (Map<String, String> stop : stops) {
            String whatsapp   = stop.get("whatsapp");
            String firstName  = stop.get("firstName");
            String deliveryDay = stop.get("deliveryDay");

            if (whatsapp != null && !whatsapp.isBlank() &&
                firstName != null && !firstName.isBlank()) {
                whatsApp.sendOnTheWay(whatsapp, firstName, deliveryDay);
                sent++;

                // 500ms pause between messages
                try { Thread.sleep(500); }
                catch (InterruptedException ignored) {}
            }
        }

        System.out.println("[Broadcast] Sent to " + sent + " customers.");

        respond(exchange, 200,
            "{\"success\":true,\"sent\":" + sent + "}");
    }

    private String queryParam(URI uri, String key) {
        String query = uri.getQuery();
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(key)) return kv[1];
        }
        return null;
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
