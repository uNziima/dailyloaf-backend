
package com.dailyloaf.handlers;

import com.dailyloaf.config.Config;
import com.dailyloaf.sheets.SheetsClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.dailyloaf.sheets.GeocodingClient;
/**
 *
 * @author Ulikhaya Mazibuko
 */
public class DeliveriesHandler implements HttpHandler {

    private final SheetsClient sheets;
    private final GeocodingClient geocoder;
    
    public DeliveriesHandler(Config config) {
        this.sheets = new SheetsClient(config);
        this.geocoder = new GeocodingClient(config.getGoogleMapsApiKey());
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // Handle CORS preflight request
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            respond(exchange, 204, "");
            return;
        }

        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method Not Allowed");
            return;
        }

        // Read the ?day=Monday query parameter
        String day = queryParam(exchange.getRequestURI(), "day");

        if (day == null || day.isBlank()) {
            respond(exchange, 400, "{\"error\":\"Missing day parameter\"}");
            return;
        }

        System.out.println("[Deliveries] Fetching orders for: " + day);

        // Fetch enriched delivery stops from Sheets
        List<Map<String, String>> stops = sheets.getDeliveriesForDay(day);

        // Enrich each stop with coordinates
        for (Map<String, String> stop : stops) {
            String customerId  = stop.get("customerId");
            String houseNumber = stop.get("houseNumber");
            String section     = stop.get("section");

            // Check saved coordinates first
            double[] saved = sheets.getSavedCoordinates(customerId);

            if (saved != null) {
                // Coordinates already saved — use immediately
                stop.put("lat", String.valueOf(saved[0]));
                stop.put("lng", String.valueOf(saved[1]));
                System.out.println("[Deliveries] Using saved coords for " + customerId);
            } else {
                // No saved coordinates — return empty now, geocode in background
                stop.put("lat", "");
                stop.put("lng", "");

                // Fire geocoding on a background thread — doesn't block response
                final String custId  = customerId;
                final String houseNum = houseNumber;
                final String sect    = section;

                new Thread(() -> {
                    double[] found = geocoder.geocode(houseNum, sect);
                    if (found != null) {
                        sheets.saveCoordinates(custId, found[0], found[1]);
                        System.out.println("[Deliveries] Background geocode saved for " + custId);
                    }
                }).start();
            }
        }
        System.out.println("[Deliveries] Found " + stops.size() + " stops.");

        // Build JSON response
        String json = buildJson(stops);
        respond(exchange, 200, json);
    }

    /**
     * Builds a JSON array from the list of delivery stops.
     */
    private String buildJson(List<Map<String, String>> stops) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < stops.size(); i++) {
            if (i > 0) sb.append(",");
            Map<String, String> stop = stops.get(i);
            sb.append("{");
            sb.append("\"orderId\":")      .append(quote(stop.get("orderId")))      .append(",");
            sb.append("\"customerId\":")   .append(quote(stop.get("customerId")))   .append(",");
            sb.append("\"firstName\":")    .append(quote(stop.get("firstName")))    .append(",");
            sb.append("\"surname\":")      .append(quote(stop.get("surname")))      .append(",");
            sb.append("\"whatsapp\":")     .append(quote(stop.get("whatsapp")))     .append(",");
            sb.append("\"section\":")      .append(quote(stop.get("section")))      .append(",");
            sb.append("\"houseNumber\":")  .append(quote(stop.get("houseNumber")))  .append(",");
            sb.append("\"whiteLoaves\":")  .append(quote(stop.get("whiteLoaves")))  .append(",");
            sb.append("\"brownLoaves\":")  .append(quote(stop.get("brownLoaves")))  .append(",");
            sb.append("\"amount\":")       .append(quote(stop.get("amount")))       .append(",");
            sb.append("\"paymentMethod\":").append(quote(stop.get("paymentMethod"))).append(",");
            sb.append("\"deliveryNotes\":").append(quote(stop.get("deliveryNotes")));
            sb.append("\"lat\":")  .append(quote(stop.get("lat")))  .append(",");
            sb.append("\"lng\":")  .append(quote(stop.get("lng")));
            sb.append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    private String quote(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\"", "\\\"").replace("\n", " ").trim() + "\"";
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