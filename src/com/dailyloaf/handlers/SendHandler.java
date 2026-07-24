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
import com.dailyloaf.model.Customer;
import com.dailyloaf.sheets.SheetsClient;


public class SendHandler implements HttpHandler {

    private final WhatsAppClient whatsApp;
    private final SheetsClient sheets;
   

    public SendHandler(Config config) {
        this.whatsApp = new WhatsAppClient(config);
        this.sheets   = new SheetsClient(config);
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

        String body    = readBody(exchange);
        String to      = Json.getString(body, "to");
        String message = Json.getString(body, "message");

            if (to == null || message == null) {
            respond(exchange, 400, "Missing to or message");
            return;
        }

        // Send the main message
        whatsApp.send(to, message);

        // If this is a payment request for a new customer —
        // follow up with a location request if no coordinates saved yet
        // Matches both PayShap and cash payment messages
        if (message.contains("payment reference") || message.contains("ready on delivery day")) {
            Customer customer = sheets.findCustomerByWhatsApp(to);
            if (customer != null) {
                double[] saved = sheets.getSavedCoordinates(customer.getCustomerId());
                if (saved == null) {
                    // Small delay so messages don't arrive simultaneously
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    whatsApp.sendLocationInstruction(to, customer.getFirstName());
                }
            }
        }

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
