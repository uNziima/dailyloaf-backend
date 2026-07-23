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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;

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
            System.err.println("[Webhook] Verification failed - token mismatch.");
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
        // Use getMessageSender to ensure we get the customer's number,
        // not the business number from the context field in interactive replies
        String from = Json.getMessageSender(json);
        if (from == null) from = Json.getString(json, "from");

 
        if (!Json.isTextMessage(json)) {
            System.out.println("[Webhook] Non-text webhook - skipping.");
            return;
        }

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
        
                // Check if this customer has a pending order waiting for payment method
        if (pendingOrders.containsKey(from)) {
            handlePaymentMethodReply(from, customer, text);
            return;
        }

        // If they just said hi, greet them back before trying to parse an order
        String normalisedText = text.trim().toLowerCase();
        if (normalisedText.matches("hi|hii|wola|hello|hey|hola|sawubona|howzit")) {
            whatsApp.send(from,
                "Hey " + customer.getFirstName() + "! " +
                "Ready to order? Just tell me what you need - " +
                "e.g. '2 white friday' or '3 white 1 brown monday'."
            );
            return;
        }

        ParsedOrder parsed = MessageParser.parse(text);

        if (parsed == null) {
            String normalised = text.trim().toLowerCase();

            // Greetings — respond personally
            if (normalised.matches("hi|Hi|Hello|hello|hey|Ola|ola|Wola|wola||awe|Awe|heyy|hola|sawubona|howzit|good morning|morning|good evening|evening|good afternoon|afternoon")) {
                whatsApp.send(from,
                    "Hey " + customer.getFirstName() + "! " +
                    "Ready to order? Just tell me what you need, " +
                    "e.g. '2 white friday' or '3 white 1 brown monday'."
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
                    "Hey " + customer.getFirstName() + ", check with us on " + config.getBusinessPhoneNumber() +
                  "if you need payment confirmation. " +
                    "Once we see your payment we'll confirm immediately."
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
            "What day : Monday, Wednesday, or Friday?"
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
                // Create order in Sheet first — get the order ID
                String orderId = sheets.createOrder(
                    customer.getCustomerId(),
                    parsed.deliveryDay,
                    parsed.whiteLoaves,
                    parsed.brownLoaves,
                    "Card Payment",
                    OrderStatus.PENDING_PAYMENT,
                    source
                );

                System.out.println("[Webhook] Order created: " + orderId);

                int total  = parsed.totalLoaves();
                int amount = total * 20;

                // Store pending order — waiting for payment method reply
                pendingOrders.put(from, new PendingOrder(
                    customer.getCustomerId(),
                    parsed.deliveryDay,
                    parsed.whiteLoaves,
                    parsed.brownLoaves,
                    total,
                    amount,
                    orderId
                ));

                // Ask for payment method
                whatsApp.send(from,
                    "Got it, " + customer.getFirstName() +
                    parsed.whiteLoaves + " white + " + parsed.brownLoaves +
                    " brown for " + parsed.deliveryDay + " = R" + amount + ".\n\n" +
                    "How are you paying?\n" +
                    "*1* - Card Payment\n" +
                    "*2* - Cash Payment"
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
       exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
       exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
       exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
       byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
       exchange.sendResponseHeaders(status, bytes.length);
       try (OutputStream os = exchange.getResponseBody()) {
           os.write(bytes);
       }
   }

    private Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new ConcurrentHashMap<>();
        String query = uri.getQuery();
        if (query == null) return params;
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) params.put(kv[0], kv[1]);
        }
        return params;
    }
    
    // Stores pending orders waiting for payment method confirmation
    // Key: WhatsApp number, Value: pending order details
    private final Map<String, PendingOrder> pendingOrders = new ConcurrentHashMap<>();
    
    private static class PendingOrder {
    String customerId;
    String deliveryDay;
    int    white;
    int    brown;
    final int    total;
    final int    amount;
    String orderId;

         PendingOrder(String customerId, String deliveryDay,
                 int white, int brown,
                 int total, int amount, String orderId) {
        this.customerId  = customerId;
        this.deliveryDay = deliveryDay;
        this.white       = white;
        this.brown       = brown;
        this.total       = total;
        this.amount      = amount;
        this.orderId     = orderId;
        }
    }
    
    private void handlePaymentMethodReply(String from, Customer customer,
                                            String text) {
          PendingOrder pending = pendingOrders.get(from);
          if (pending == null) return;

          String reply = text.trim().toLowerCase();

          String paymentMethod;
          String paymentInstruction;

          switch (reply) {
              case "1", "Card", "Card Payment", "eft" -> {
                  paymentMethod      = "Card Payment";
                  paymentInstruction =
                      "Send R" + pending.amount + " to Capitec Account Number : 1055617264. " +
                      "Use *" + pending.orderId + "* as your reference. " +
                      "Once we see it you're confirmed.";
              }
              case "2", "Cash Payment", "Cash", "CP" -> {
                  paymentMethod      = "Cash Payment";
                  paymentInstruction =
                    "Have R" + pending.amount + " ready on delivery day. " +
                    "Your order is locked in.\n\n" +
                    "If you'd like to pay before delivery, call us on " +
                    config.getBusinessPhoneNumber() + " and we'll arrange it.";
              }
              default -> {
                  // Didn't understand — ask again
                  whatsApp.send(from,
                      "Please reply *1* for Card Payment or *2* for Cash Payment."
                  );
                  return;
              }
          }

          // Remove from pending — payment method confirmed
          pendingOrders.remove(from);

          // Update order payment method in Sheet
          sheets.updateOrderPaymentMethod(pending.orderId, paymentMethod);

          // Send confirmation
          whatsApp.send(from,
              "Confirmed, " + customer.getFirstName() + ". " +
              "Your order *" + pending.orderId + "* — " +
              pending.white + " white + " + pending.brown + " brown for " +
              pending.deliveryDay + ". " + paymentInstruction
          );

          // If customer has no saved location yet — request it now
            double[] saved = sheets.getSavedCoordinates(customer.getCustomerId());
            if (saved == null) {
                whatsApp.sendLocationInstruction(from, customer.getFirstName());
            }
        }

        private void handleLocationShare(String from, Customer customer, String json) {
        Double lat = Json.getDouble(json, "latitude");
        Double lng = Json.getDouble(json, "longitude");

        if (lat == null || lng == null) {
            System.err.println("[Webhook] Location message, no coordinates found.");
            return;
        }

        System.out.println("[Webhook] Location received from " + from +
                           ": " + lat + ", " + lng);

        sheets.saveCoordinates(customer.getCustomerId(), lat, lng);

        whatsApp.send(from,
            "Perfect, " + customer.getFirstName() + ". " +
            "We've saved your location, we'll find your door on delivery day."
        );
    }
}