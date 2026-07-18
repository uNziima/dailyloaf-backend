package com.dailyloaf.handlers;

import com.dailyloaf.config.Config;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public class ConfigHandler implements HttpHandler {

    private final Config config;
    private Object key;

    public ConfigHandler(Config config) {
        this.config = config;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            respond(exchange, 204, "");
            return;
        }

        String expectedKey = config.getDeliveryOsKey();
            if (expectedKey == null || !expectedKey.equals(key)) {
                respond(exchange, 403, "{\"error\":\"Forbidden\"}");
                return;
            }

        String mapsKey = config.getGoogleMapsApiKey();
        if (mapsKey == null) mapsKey = "";
        String json = "{\"mapsKey\":\"" + mapsKey + "\"}";
    }

    private String queryParam(URI uri, String name) {
        String query = uri.getQuery();
        if (query == null) return "";
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(name)) return kv[1];
        }
        return "";
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
