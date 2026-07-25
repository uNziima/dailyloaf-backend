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
    
    // Tracks customers who have sent "please call me" and are
    // waiting to send their callback number
    private final Map<String, Boolean> awaitingCallback =
        new ConcurrentHashMap<>();

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
        String from = Json.getMessageSender(json);
        if (from == null) from = Json.getString(json, "from");

        // CHECK LOCATION FIRST — before isTextMessage filter
        if (Json.isLocationMessage(json)) {
            if (from != null) {
                Customer customer = sheets.findCustomerByWhatsApp(from);
                if (customer != null) {
                    handleLocationShare(from, customer, json);
                } else {
                    System.out.println("[Webhook] Location from unknown number: " + from);
                }
            }
            return;
        }
        
        // Now filter non-text messages
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
        
                // ── Order lookup ──────────────────────────────────────────
        // Detects 7-digit reference numbers like "2026006"
        if (MessageParser.isOrderLookup(text)) {
            handleOrderLookup(from, customer, text);
            return;
        }

        // ── Call request ──────────────────────────────────────────
        // Detects "please call me" or "please call"
        if (MessageParser.isCallRequest(text)) {
            handleCallRequest(from, customer);
            return;
        }

        // ── Awaiting callback number ──────────────────────────────
        // Customer previously sent "please call me" and we asked
        // for their number — this is their response
        if (awaitingCallback.containsKey(from)) {
            handleCallbackNumber(from, customer, text);
            return;
        }

        
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
                    "Pending",
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
                    "Got it, " + customer.getFirstName() + " " +
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
                      "Send R" + pending.amount + " to Capitec Account: " + config.getCapitecNumber() + ". " +
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
        
    /**
    * Handles order lookup when customer sends their 7-digit reference.
    * Converts "2026006" → "ORD-2026-006" and returns full order summary.
    * If customer has never paid before, adds security notice and location request.
    */
   private void handleOrderLookup(String from, Customer customer, String text) {
       String orderId = MessageParser.toOrderId(text.trim());
       if (orderId == null) return;

       System.out.println("[Webhook] Order lookup: " + orderId +
                          " by " + customer.getCustomerId());

       Map<String, String> order = sheets.getOrderById(orderId);

       if (order == null) {
           // Order not found — let customer know
           whatsApp.send(from,
               "Hi " + customer.getFirstName() + ", we couldn't find " +
               "order *" + orderId + "*. " +
               "Please check the reference number and try again."
           );
           return;
       }

       // Security check — verify this order belongs to this customer
       if (!customer.getCustomerId().trim()
                    .equals(order.get("customerId").trim())) {
           whatsApp.send(from,
               "Sorry " + customer.getFirstName() + ", that order " +
               "doesn't match your account. " +
               "Please check the reference number."
           );
           return;
       }

       // Send the order summary with payment details
       whatsApp.sendOrderSummary(from, customer.getFirstName(),
           order, config.getCapitecNumber());

       // Check if this is a first-time customer who has never paid
       boolean hasPaidBefore = sheets.hasEverPaidOrder(
           customer.getCustomerId()
       );

       if (!hasPaidBefore) {
           // Pause so messages don't arrive simultaneously
           try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

           // Send first-time security notice
           whatsApp.sendFirstTimeSecurityNotice(from, customer.getFirstName());

           // Follow with location request if no coordinates saved yet
           try { Thread.sleep(1500); } catch (InterruptedException ignored) {}

           double[] saved = sheets.getSavedCoordinates(
               customer.getCustomerId()
           );
           if (saved == null) {
               whatsApp.sendLocationInstruction(from, customer.getFirstName());
           }
       }
   }

   /**
    * Handles "please call me" from a customer.
    * Marks the customer as awaiting their callback number
    * and asks them to send it.
    */
   private void handleCallRequest(String from, Customer customer) {
       System.out.println("[Webhook] Call request from: " + customer.getFullName());

       // Flag this number as awaiting a callback number response
       awaitingCallback.put(from, true);

       whatsApp.send(from,
           "Of course, " + customer.getFirstName() + ". " +
           "Please type and send your cellphone number " +
           "and we'll call you back shortly."
       );
   }

   /**
    * Handles the phone number sent after a call request.
    * Validates the format, confirms to the customer,
    * and alerts both Nziima and Ntobeko.
    */
   private void handleCallbackNumber(String from, Customer customer,
                                      String text) {
       String raw        = text.trim();
       String errorMsg   = validateCallbackNumber(raw);

       if (errorMsg != null) {
           // Validation failed — explain exactly what's wrong
           // Customer stays in awaitingCallback state so they can try again
           whatsApp.send(from, errorMsg);
           return;
       }

       // Valid number — remove from awaiting state
       awaitingCallback.remove(from);

       // Build display-friendly number
       String cleaned = raw.replaceAll("[\\s\\-]", "");
       String display = cleaned.startsWith("+") ? cleaned :
                        cleaned.startsWith("27") ? "+" + cleaned :
                        "+27" + cleaned.substring(1);

       // Confirm to customer
       whatsApp.sendCallbackConfirmation(from, customer.getFirstName(), display);

       // Alert both founders
       String nziimaNumber  = config.getNziimaWhatsApp();
       String ntobekoNumber = config.getNtobekoWhatsApp();

       if (nziimaNumber != null && !nziimaNumber.isBlank()) {
           whatsApp.sendCallbackAlert(
               nziimaNumber,
               customer.getFullName(),
               display,
               from
           );
       }

       if (ntobekoNumber != null && !ntobekoNumber.isBlank()) {
           whatsApp.sendCallbackAlert(
               ntobekoNumber,
               customer.getFullName(),
               display,
               from
           );
       }

       System.out.println("[Webhook] Callback alert sent for: " +
                          customer.getFullName() + " → " + display);
   }

   /**
    * Validates a South African phone number sent as a callback request.
    *
    * Rules:
    *   Starting with 0    → must be exactly 10 digits total
    *   Starting with 27   → must be exactly 11 digits total
    *   Starting with +27  → must be exactly 12 characters total (+27 + 9 digits)
    *   Anything else      → rejected with explanation
    *
    * Returns null if valid.
    * Returns an error message string if invalid.
    */
   private String validateCallbackNumber(String raw) {
       if (raw == null || raw.isBlank()) {
           return "Please send your cellphone number so we can call you back.";
       }

       // Remove spaces and dashes before validating
       String cleaned = raw.replaceAll("[\\s\\-]", "");

       if (cleaned.startsWith("+27")) {
           // +27 followed by 9 digits = 12 chars total
           if (cleaned.length() != 12) {
               return "The number starting with +27 should have exactly " +
                      "9 digits after +27 — 12 characters total. " +
                      "Example: *+27821234567*. Please try again.";
           }
           return null; // valid
       }

       if (cleaned.startsWith("27")) {
           // 27 followed by 9 digits = 11 digits total
           if (cleaned.length() != 11) {
               return "The number starting with 27 should be exactly " +
                      "11 digits total. " +
                      "Example: *27821234567*. Please try again.";
           }
           return null; // valid
       }

       if (cleaned.startsWith("0")) {
           // 0 followed by 9 digits = 10 digits total
           if (cleaned.length() != 10) {
               return "The number starting with 0 should be exactly " +
                      "10 digits total. " +
                      "Example: *0821234567*. Please try again.";
           }
           return null; // valid
       }

       // Doesn't match any known South African format
       return "That doesn't look like a valid South African number. " +
              "Please start with *0*, *27*, or *+27*. " +
              "Example: *0821234567*.";
   }
}