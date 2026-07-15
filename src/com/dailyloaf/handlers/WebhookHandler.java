package com.dailyloaf.handlers;

import com.dailyloaf.config.Config;
import com.dailyloaf.model.Customer;
import com.dailyloaf.model.OrderStatus;
import com.dailyloaf.sheets.SheetsClient;
import com.dailyloaf.util.Json;
import com.dailyloaf.validation.OrderValidator;
import com.dailyloaf.whatsapp.MessageParser;
import com.dailyloaf.whatsapp.MessageParser.ParsedOrder;
import com.dailyloaf.whatsapp.WhatsAppClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebhookHandler implements HttpHandler {

    private static final String FORM_LINK = "https://forms.gle/LZceix3qQm8G1WdQ7";

    private final Config          config;
    private final WhatsAppClient  whatsApp;
    private final SheetsClient    sheets;
    private final ExecutorService executor;

    public WebhookHandler(Config config) {
        this.config   = config;
        this.whatsApp = new WhatsAppClient(config);
        this.sheets   = new SheetsClient(config);
        this.executor = Executors.newCachedThreadPool();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();

        switch (method) {
            case "GET"  -> handleVerification(exchange);
            case "POST" -> handleIncoming(exchange);
            default     -> respond(exchange, 405, "Method Not Allowed");
        }
    }

    private void handleVerification(HttpExchange exchange) throws IOException {
        Map<String, String> params = queryParams(exchange.getRequestURI());

        String mode      = params.get("hub.mode");
        String token     = params.get("hub.verify_token");
        String challenge = params.get("hub.challenge");

        if ("subscribe".equals(mode) &&
            config.getWebhookVerifyToken().equals(token)) {
            System.out.println("[Webhook] Verified successfully.");
            respond(exchange, 200, challenge != null ? challenge : "");
        } else {
            System.err.println("[Webhook] Verification failed — token mismatch.");
            respond(exchange, 403, "Forbidden");
        }
    }

    private void handleIncoming(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        respond(exchange, 200, "OK");

        executor.submit(() -> {
            try {
                processMessage(body);
            } catch (Exception e) {
                System.err.println("[Webhook] Processing error: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void processMessage(String json) {
        if (!Json.isTextMessage(json)) {
            System.out.println("[Webhook] Non-text webhook — skipping.");
            return;
        }

        String from = Json.getString(json, "from");
        String text = Json.getString(json, "body");

        if (from == null || text == null) {
            System.err.println("[Webhook] Could not extract from/body: " + json);
            return;
        }

        System.out.println("[Webhook] Message from " + from + ": " + text);

        Customer customer = sheets.findCustomerByWhatsApp(from);

        if (customer == null) {
            System.out.println("[Webhook] New customer: " + from);
            whatsApp.sendNewCustomerGreeting(from, FORM_LINK);
            return;
        }

        System.out.println("[Webhook] Returning customer: " + customer);

        ParsedOrder parsed = MessageParser.parse(text);

        if (parsed == null) {
            String normalised = text.trim().toLowerCase();

            // Greetings — respond personally
            if (normalised.matches("hi|Hi|Hello|hello|hey|Ola|ola|Wola|wola||awe|Awe|heyy|hola|sawubona|howzit|good morning|morning|good evening|evening|good afternoon|afternoon")) {
                whatsApp.send(from,
                    "Hey " + customer.getFirstName() + "! " +
                    "Ready to order? Just tell me what you need - " +
                    "e.g. '2 white friday' or 'same monday'."
                );
                return;
            }

            // Gratitude — acknowledge warmly
            if (normalised.matches("thanks|thank you|dankie|ngyabonga|danko|danki|ngiyabonga|cheers|appreciated|thx|ty")) {
                whatsApp.send(from,
                    "Always, " + customer.getFirstName() + ". See you on delivery day."
                );
                return;
            }

            // Payment status check
            if (normalised.matches("paid|payment|confirmed|did you get it|have you received|status|my order")) {
                whatsApp.send(from,
                    "Hey " + customer.getFirstName() + ", check with us on " +
                            //I NEED TO ADD BUSINESS NUMBER HERE
                  "0XX XXX XXXX if you need payment confirmation. " +
                    "Once we see your PayShap we'll confirm immediately."
                );
                return;
            }

            // Genuinely unrecognised — send help
            whatsApp.sendReturningCustomerHelp(from, customer.getFirstName());
            return;
        }

        if (parsed.isSameAsLast) {
            handleSameOrder(from, customer, parsed);
            return;
        }

        if (!parsed.hasDay()) {
            whatsApp.send(from,
                "Which day, " + customer.getFirstName() +
                "? Reply Monday, Wednesday, or Friday."
            );
            return;
        }

        placeOrder(from, customer, parsed, "WhatsApp");
    }

    private void handleSameOrder(String from, Customer customer,
                                 ParsedOrder parsed) {
        whatsApp.send(from,
            "Got it, " + customer.getFirstName() + "! " +
            "What day - Monday, Wednesday, or Friday?"
        );
    }

    private void placeOrder(String from, Customer customer,
                            ParsedOrder parsed, String source) {

        OrderValidator.Result result = OrderValidator.validate(
            parsed.whiteLoaves,
            parsed.brownLoaves,
            parsed.deliveryDay
        );

        switch (result) {

            case BELOW_MINIMUM -> {
                whatsApp.sendMinimumOrderNotice(from, customer.getFirstName());
            }

            case AFTER_CUTOFF -> {
                whatsApp.sendCutOffNotice(
                    from,
                    customer.getFirstName(),
                    parsed.deliveryDay,
                    OrderValidator.nextSlot(parsed.deliveryDay)
                );
            }

            case MISSING_DAY, INVALID_DAY -> {
                whatsApp.send(from,
                    "Which day, " + customer.getFirstName() +
                    "? Reply Monday, Wednesday, or Friday."
                );
            }

            case VALID -> {
                String orderId = sheets.createOrder(
                    customer.getCustomerId(),
                    parsed.deliveryDay,
                    parsed.whiteLoaves,
                    parsed.brownLoaves,
                    "PayShap",
                    OrderStatus.PENDING_PAYMENT,
                    source
                );

                System.out.println("[Webhook] Order created: " + orderId);

                whatsApp.sendPaymentRequest(
                    from,
                    customer.getFirstName(),
                    parsed.whiteLoaves,
                    parsed.brownLoaves,
                    parsed.totalLoaves(),
                    parsed.totalLoaves() * 20
                );
            }
        }
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream is = exchange.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void respond(HttpExchange exchange, int status,
                         String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new HashMap<>();
        String query = uri.getQuery();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) params.put(kv[0], kv[1]);
        }
        return params;
    }
}