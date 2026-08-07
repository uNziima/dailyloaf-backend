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
import java.util.List;

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

        // Log every incoming webhook payload — confirms Java is receiving
        System.out.println("[Webhook] Incoming POST received. Body length: " +
                           body.length() + " chars");

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

        System.out.println("[Webhook] Processing from: " + from);

        // 1. Location share — always check first
        if (Json.isLocationMessage(json)) {
            if (from != null) {
                Customer customer = sheets.findCustomerByWhatsApp(from);
                if (customer != null) {
                    handleLocationShare(from, customer, json);
                } else {
                    System.out.println("[Webhook] Location from unknown: " + from);
                }
            }
            return;
        }

        // 2. Text only from here
        if (!Json.isTextMessage(json)) {
            System.out.println("[Webhook] Non-text webhook — skipping.");
            return;
        }

        String text = Json.getString(json, "body");
        if (text == null || text.isBlank()) return;

        System.out.println("[Webhook] Message from " + from + ": " + text);

        // 3. New or returning?
        Customer customer = sheets.findCustomerByWhatsApp(from);

        if (customer == null) {
            System.out.println("[Webhook] New customer: " + from);
            whatsApp.sendNewCustomerGreeting(from, FORM_LINK);
            return;
        }

        System.out.println("[Webhook] Returning: " + customer);

        // 4. Order lookup — 7 digits
        if (MessageParser.isOrderLookup(text)) {
            handleOrderLookup(from, customer, text);
            return;
        }

        // 5. Call request
        if (MessageParser.isCallRequest(text)) {
            awaitingYesNo.remove(from);
            awaitingMenuChoice.remove(from);
            handleCallRequest(from, customer);
            return;
        }

        // 6. Phone number reply for callback
        if (MessageParser.isPhoneNumber(text)) {
            awaitingYesNo.remove(from);
            awaitingMenuChoice.remove(from);
            handleCallbackNumber(from, customer, text);
            return;
        }

        // 7. Customer awaiting YES/NO response
        if (awaitingYesNo.containsKey(from)) {
            handleYesNoResponse(from, customer, text);
            return;
        }

        // 8. Customer awaiting menu choice
        if (awaitingMenuChoice.containsKey(from)) {
            handleMenuChoice(from, customer, text);
            return;
        }

        // 9. Pending payment method selection
        if (pendingOrders.containsKey(from)) {
            handlePaymentMethodReply(from, customer, text);
            return;
        }

        // 10. Parse as order
        MessageParser.ParsedOrder parsed = MessageParser.parse(text);

        if (parsed == null) {
            handleConversational(from, customer, text);
            return;
        }

        if (parsed.isSameAsLast) {
            handleSameOrder(from, customer, parsed);
            return;
        }

        if (!parsed.hasDay()) {
            whatsApp.send(from,
                "Which day, " + customer.getFirstName() +
                "? Reply *Monday*, *Wednesday*, or *Friday*."
            );
            return;
        }

        placeOrder(from, customer, parsed, "WhatsApp");
    }
    
    /**
    * Handles conversational messages — greetings, gratitude, status queries.
    * Anything genuinely unrecognised triggers the "are you trying to order?" prompt.
    */
   private void handleConversational(String from, Customer customer, String text) {
       String t = text.trim().toLowerCase();

       // Greetings
       if (t.matches("hi|hello|hey|hola|sawubona|howzit|sanibona|" +
                     "good morning|morning|good evening|evening|" +
                     "good afternoon|afternoon")) {
           whatsApp.send(from,
               "Hey " + customer.getFirstName() + " 👋\n\n" +
               "Good to hear from you! Ready to order?\n\n" +
               "Reply *YES* to place an order\n" +
               "Reply *NO* for other options"
           );
           awaitingYesNo.put(from, true);
           return;
       }

       // Gratitude
       if (t.matches("thanks|thank you|dankie|ngiyabonga|enkosi|" +
                     "cheers|appreciated|thx|ty")) {
           whatsApp.send(from,
               "Always, " + customer.getFirstName() +
               " 🙏 See you on delivery day.\n\n" +
               "_No reply needed_"
           );
           return;
       }

       // Payment status query
       if (t.matches("paid|payment|confirmed|did you get it|" +
                     "have you received|status|my order|order")) {
           whatsApp.send(from,
               "Hi " + customer.getFirstName() + ",\n\n" +
               "To check your order, send your *7-digit order reference*.\n\n" +
               "_Example: if your order ID is ORD-2026-006, send *2026006*_"
           );
           return;
       }

       // Unrecognised — trigger yes/no prompt
       awaitingYesNo.put(from, true);
       whatsApp.sendAreYouOrdering(from, customer.getFirstName());
   }

   /**
    * Handles YES or NO response after "Are you trying to order?" prompt.
    *
    * YES → send ordering instructions
    * NO  → send options menu, move to menu state
    * Other → error, keep them in yes/no state so they can try again
    */
   private void handleYesNoResponse(String from, Customer customer,
                                     String text) {
       if (MessageParser.isYes(text)) {
           awaitingYesNo.remove(from);
           whatsApp.sendOrderingInstructions(from, customer.getFirstName());

       } else if (MessageParser.isNo(text)) {
           awaitingYesNo.remove(from);
           awaitingMenuChoice.put(from, true);
           whatsApp.sendNoMenu(from, customer.getFirstName());

       } else {
           // Invalid response — remind them to say yes or no
           whatsApp.send(from,
               "⚠️ Please reply *YES* or *NO*, " +
               customer.getFirstName() + "."
           );
       }
   }

   /**
    * Handles the customer's selection from the options menu (1-5).
    *
    * 1 — Check order status → ask for order reference number
    * 2 — Request callback  → same as "please call me"
    * 3 — Delivery issue    → prompt to describe problem
    * 4 — Subscriptions     → send subscription offer
    * 5 — Something else    → open-ended prompt
    * Other → error, repeat the menu
    */
   private void handleMenuChoice(String from, Customer customer, String text) {
       int choice = MessageParser.getMenuChoice(text);

       if (choice == -1) {
           // Not a valid menu number — repeat the menu
           whatsApp.sendMenuError(from, customer.getFirstName());
           return;
       }

       // Valid choice — clear menu state
       awaitingMenuChoice.remove(from);

       switch (choice) {
           case 1 -> {
               // Check order status
               whatsApp.send(from,
                   "To check your order, send your *7-digit order reference*.\n\n" +
                   "_Example: if your order ID is ORD-2026-006, send *2026006*_"
               );
           }
           case 2 -> {
               // Request callback — reuse existing flow
               handleCallRequest(from, customer);
           }
           case 3 -> {
               // Delivery issue
               whatsApp.sendDeliveryIssuePrompt(from, customer.getFirstName());
           }
           case 4 -> {
               // Subscriptions
               whatsApp.sendSubscriptionOffer(
                   from,
                   customer.getFirstName(),
                   "your next delivery day"
               );
           }
           case 5 -> {
               // Something else
               whatsApp.sendSomethingElsePrompt(from, customer.getFirstName());
           }
       }
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

                pendingOrders.put(from, new PendingOrder(
                    customer.getCustomerId(),
                    parsed.deliveryDay,
                    parsed.whiteLoaves,
                    parsed.brownLoaves,
                    total,
                    amount,
                    orderId
                ));

                // Use the new structured payment method prompt
                whatsApp.sendPaymentMethodPrompt(
                    from,
                    customer.getFirstName(),
                    parsed.whiteLoaves,
                    parsed.brownLoaves,
                    parsed.deliveryDay,
                    amount
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

    // Tracks customers waiting to respond YES or NO to "are you trying to order?"
    private final Map<String, Boolean> awaitingYesNo =
        new ConcurrentHashMap<>();

    // Tracks customers who said NO and are now choosing from the options menu
    private final Map<String, Boolean> awaitingMenuChoice =
        new ConcurrentHashMap<>();
    
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
                System.err.println("[Webhook] Location — no coordinates found.");
                return;
            }

            System.out.println("[Webhook] Location from " + from +
                               ": " + lat + ", " + lng);

            sheets.saveCoordinates(customer.getCustomerId(), lat, lng);

            // Use the dedicated confirmed message instead of a raw send()
            whatsApp.sendLocationConfirmed(from, customer.getFirstName());
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

   private void handleCallRequest(String from, Customer customer) {
        System.out.println("[Webhook] Call request from: " + customer.getFullName());
        whatsApp.sendCallbackNumberPrompt(from, customer.getFirstName());
    }

   /**
    * Handles the phone number sent after a customer types "please call me".
    *
    * Flow:
    * 1. Validate the number format
    * 2. If invalid — explain exactly what's wrong and why
    * 3. If valid — confirm to customer, load founder numbers from Sheets,
    *    send alert to every founder
    *
    * Validation rules:
    *   Starts with 0    → must be exactly 10 digits
    *   Starts with 27   → must be exactly 11 digits
    *   Starts with +27  → must be exactly 12 characters
    *   Anything else    → rejected with explanation
    */
   private void handleCallbackNumber(String from, Customer customer,
                                      String text) {
       String raw      = text.trim();
       String errorMsg = validateCallbackNumber(raw);

       if (errorMsg != null) {
           // Validation failed — tell customer exactly what's wrong
           // They can try again — stateless so no cleanup needed
           whatsApp.send(from, errorMsg);
           return;
       }

       // Build a consistent display format for the number
       String cleaned = raw.replaceAll("[\\s\\-]", "");
       String display = cleaned.startsWith("+") ? cleaned :
                        cleaned.startsWith("27") ? "+" + cleaned :
                        "+27" + cleaned.substring(1);

       System.out.println("[Webhook] Callback number received: " + display +
                          " from " + customer.getFullName());

       // Confirm to customer immediately
       whatsApp.sendCallbackConfirmation(from, customer.getFirstName(), display);

       // Load founder numbers from CONTACTS tab in Google Sheets
       List<String> founders = sheets.getContactNumbers();

       if (founders.isEmpty()) {
           System.err.println("[Webhook] No contact numbers found in CONTACTS tab.");
           return;
       }

       // Alert every founder listed in the CONTACTS tab
       for (String founderNumber : founders) {
           System.out.println("[Webhook] Sending callback alert to: " + founderNumber);
           whatsApp.sendCallbackAlert(
               founderNumber,
               customer.getFullName(),
               display,
               from
           );
       }
   }

   /**
    * Validates a South African callback number sent by a customer.
    *
    * Returns null if the number is valid.
    * Returns a descriptive error message if invalid — the message
    * explains exactly what is wrong so the customer knows what to fix.
    */
   private String validateCallbackNumber(String raw) {
       if (raw == null || raw.isBlank()) {
           return "Please send your cellphone number so we can call you back.\n\n" +
                  "Example: *0821234567*";
       }

       // Remove spaces and dashes — customer may format number differently
       String cleaned = raw.replaceAll("[\\s\\-]", "");

       if (cleaned.startsWith("+27")) {
           // +27 + 9 digits = 12 characters total
           if (cleaned.length() != 12) {
               return "The number you sent starts with *+27* but has " +
                      (cleaned.length() - 3) + " digits after +27 — " +
                      "it should have exactly 9 digits after +27.\n\n" +
                      "Example: *+27821234567* (12 characters total). " +
                      "Please try again.";
           }
           return null; // valid
       }

       if (cleaned.startsWith("27")) {
           // 27 + 9 digits = 11 digits total
           if (cleaned.length() != 11) {
               return "The number you sent starts with *27* but has " +
                      cleaned.length() + " digits — " +
                      "it should be exactly 11 digits total.\n\n" +
                      "Example: *27821234567* (11 digits). " +
                      "Please try again.";
           }
           return null; // valid
       }

       if (cleaned.startsWith("0")) {
           // 0 + 9 digits = 10 digits total
           if (cleaned.length() != 10) {
               return "The number you sent starts with *0* but has " +
                      cleaned.length() + " digits — " +
                      "it should be exactly 10 digits total.\n\n" +
                      "Example: *0821234567* (10 digits). " +
                      "Please try again.";
           }
           return null; // valid
       }

       // Doesn't match any known South African format
       return "That doesn't look like a valid South African number.\n\n" +
              "Please start your number with:\n" +
              "• *0* — e.g. 0821234567\n" +
              "• *27* — e.g. 27821234567\n" +
              "• *+27* — e.g. +27821234567";
   }
}