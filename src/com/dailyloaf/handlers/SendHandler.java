package com.dailyloaf.handlers;

import com.dailyloaf.config.Config;
import com.dailyloaf.util.Json;
import com.dailyloaf.whatsapp.WhatsAppClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class SendHandler implements HttpHandler {

    private final WhatsAppClient whatsApp;

    public SendHandler(Config config) {
        this.whatsApp = new WhatsAppClient(config);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, "Method Not Allowed");
            return;
        }

        String body = readBody(exchange);
        String to      = Json.getString(body, "to");
        String message = Json.getString(body, "message");

        if (to == null || message == null) {
            respond(exchange, 400, "Missing to or message");
            return;
        }

        whatsApp.send(to, message);
        respond(exchange, 200, "OK");
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
       byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
       exchange.sendResponseHeaders(status, bytes.length);
       try (OutputStream os = exchange.getResponseBody()) {
           os.write(bytes);
       }
   }
}
