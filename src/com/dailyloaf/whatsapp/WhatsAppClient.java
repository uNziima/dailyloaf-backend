package com.dailyloaf.whatsapp;

import com.dailyloaf.config.Config;
import com.dailyloaf.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public class WhatsAppClient {

    private static final String BASE_URL  = "https://graph.facebook.com/v18.0/";
    private static final int    TIMEOUT_S = 15;

    private final Config     config;
    private final HttpClient http;

    public WhatsAppClient(Config config) {
        this.config = config;
        this.http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT_S))
            .build();
    }

    public void send(String to, String message) {
        String url  = BASE_URL + config.getWhatsAppPhoneNumberId() + "/messages";
        String body = buildPayload(to, message);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.getWhatsAppToken())
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(TIMEOUT_S))
            .build();

        try {
            HttpResponse<String> response = http.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );
            if (response.statusCode() == 200) {
                System.out.println("[WhatsApp] Sent to " + to + ": " + message);
            } else {
                System.err.println("[WhatsApp] Failed (" + response.statusCode() +
                                   ") to " + to + ": " + response.body());
            }
        } catch (IOException | InterruptedException e) {
            System.err.println("[WhatsApp] Error sending to " + to +
                               ": " + e.getMessage());
        }
    }

    private String buildPayload(String to, String message) {
        return """
               {
                 "messaging_product": "whatsapp",
                 "to": "%s",
                 "type": "text",
                 "text": { "body": "%s" }
               }
               """.formatted(to, Json.escape(message));
    }

    public void sendPaymentRequest(String to, String firstName,
                                    int white, int brown,
                                    int total, int amount,
                                    String orderId) {
         send(to, String.format(
             "Hi %s Your order: %d white + %d brown = %d loaves. " +
             "Total: R%d. Send to " + config.getCapitecNumber() +
             "Use *%s* as your payment reference. " +
             "Once we see it, you're confirmed.",
             firstName, white, brown, total, amount, orderId
         ));
        }

    public void sendPaymentConfirmed(String to, String firstName,
                                     String deliveryDay) {
        send(to, String.format(
            "Confirmed, %s. See you %s morning.",
            firstName, deliveryDay
        ));
    }

    public void sendMinimumOrderNotice(String to, String firstName) {
        send(to, String.format(
            "Hey %s, our minimum is 2 loaves. " +
            "Just reply with your updated quantity and we'll sort it.",
            firstName
        ));
    }

    public void sendCutOffNotice(String to, String firstName,
                                 String requestedDay, String nextSlot) {
        send(to, String.format(
            "%s, orders for %s closed at 8pm. " +
            "Next slot is %s. Want me to lock that in for you?",
            firstName, requestedDay, nextSlot
        ));
    }

    public void sendEveningReminder(String to, String firstName,
                                    int totalLoaves) {
        send(to, String.format(
            "Morning comes fast, %s We've got your %d loaf%s for tomorrow. See you then.",
            firstName, totalLoaves, totalLoaves == 1 ? "" : "s"
        ));
    }

      /**
    * Sends the "please call me" hint to the customer.
    * Appended to the post-delivery message so every customer
    * knows this option exists after their first delivery.
    * Not a separate message — part of the delivery confirmation.
    */
    public void sendPostDelivery(String to, String firstName) {
        send(to, String.format(
            "Hope the bread is fresh, %s. " +
           "If your neighbour wants this, send them our way.\n\n" +
           "💡 Need help? Just type and send us *please call me* anytime.",
           firstName
        ));
    }

    public void sendCannotFindAddress(String to, String firstName,
                                      String businessNumber) {
        send(to, String.format(
            "%s, we're nearby but can't find your place. " +
            "Can you call us on %s? We're 2 minutes away.",
            firstName, businessNumber
        ));
    }

    public void sendLeftWithNeighbour(String to, String firstName,
                                      String neighbourName) {
        send(to, String.format(
            "%s, you weren't home so we left your bread with %s next door.",
            firstName, neighbourName
        ));
    }

    public void sendSubscriptionOffer(String to, String firstName,
                                      String deliveryDay) {
        send(to, String.format(
            "Hey %s, want to make this automatic? " +
            "Tell me your usual order and I'll lock you in every %s. " +
            "Pay monthly and save 10%%. Just say YES.",
            firstName, deliveryDay
        ));
    }

    public void sendTopThreeAcknowledgement(String to, String firstName) {
        send(to, String.format(
            "Hey %s, you're one of our top 3 customers this month. " +
            "Genuinely appreciate it.",
            firstName
        ));
    }

    public void sendNewCustomerGreeting(String to, String formLink) {
        send(to,
            """
            Hi! Welcome to Daily Loaf
            We deliver fresh bread to your door in Ikhwezi, Section 01 & Section 02 every Mon, Wed & Fri morning.
            
            Fill in this quick form to place your first order:
            """ +
            formLink + "\n\nTakes 2 minutes."
        );
    }

    public void sendReturningCustomerHelp(String to, String firstName) {
        send(to, String.format("""
                               Hey %s! To order just reply with your quantities and day.
                               Example: '2 white monday' or '1 white 1 brown friday'
                               Or reply 'same' to repeat your last order.""",
            firstName
        ));
    }
    
    public void sendOnTheWay(String to, String firstName, 
                            String deliveryDay) {
      send(to, String.format(
          "Morning %s! Your bread is on the way. " +
          "We'll be there shortly. " +
          "Please ensure that your cellphone is switched on. ",
          firstName
      ));
    }
    
    public void sendWeAreOutside(String to, String firstName) {
        send(to, String.format(
            "Hi %s, we're outside your gate with your bread. " +
            "Come collect when you're ready.",
            firstName
        ));
    }
    
    /**
    * Sends a location request to the customer after payment confirmation.
    * Uses WhatsApp Cloud API interactive message type "location_request_message".
    * Customer sees a "Send Location" button — one tap shares their GPS coordinates.
    */
   public void sendLocationInstruction(String to, String firstName) {
        send(to, String.format(
            "One last thing, %s please share your WhatsApp location " +
            "so we can find your door on delivery day.\n\n" +
            "Tap the 📎 attachment icon → Location → " +
            "Send Your Current Location.",
            firstName
        ));
    }
   
   /**
    * Sends a full order summary when a customer looks up their order
    * by sending the 7-digit reference number.
    * Includes payment instructions and both payment options.
    */
   public void sendOrderSummary(String to, String firstName,
                                 Map<String, String> order,
                                 String capitecNumber) {
       String orderId      = order.get("orderId");
       String white        = order.get("whiteLoaves");
       String brown        = order.get("brownLoaves");
       String amount       = order.get("amount");
       String payment      = order.get("paymentMethod");
       String deliveryDate = order.get("deliveryDate");
       String status       = order.get("status");

       // Human-readable status text
       String statusText = switch (status != null ? status : "") {
           case "PENDING_PAYMENT" -> "Awaiting payment";
           case "PAID"            -> "Payment confirmed ✓";
           case "DELIVERED"       -> "Delivered ✓";
           case "CANCELLED"       -> "Cancelled";
           default                -> status != null ? status : "Unknown";
       };

       send(to, String.format(
           "Hi %s, here's your order summary:\n\n" +
           "*Order:* %s\n" +
           "*Loaves:* %s white + %s brown\n" +
           "*Delivery:* %s\n" +
           "*Amount:* R%s\n" +
           "*Payment:* %s\n" +
           "*Status:* %s\n\n" +
           "To pay via Card/PayShap: send R%s to Capitec Account *%s*, " +
           "use *%s* as your reference.\n" +
           "To pay cash on delivery: reply *CASH* to arrange.",
           firstName, orderId, white, brown,
           deliveryDate, amount, payment, statusText,
           amount, capitecNumber, orderId
       ));
   }

   /**
    * Sends the first-time customer security notice.
    * Only fires when a customer has never successfully paid before.
    * Explains why upfront payment is required for new customers.
    */
   public void sendFirstTimeSecurityNotice(String to, String firstName) {
       send(to, String.format(
           "📋 *Payment Notice*\n\n" +
           "Hi %s, for new customers we require payment before delivery. " +
           "This protects both parties and ensures your bread is ready on " +
           "delivery day.\n\n" +
           "Once your first payment is confirmed, future orders can be " +
           "paid on delivery if you prefer.",
           firstName
       ));
   }

   /**
    * Confirms to the customer that we received their callback request
    * and will call them back on the number they provided.
    */
   public void sendCallbackConfirmation(String to, String firstName,
                                        String callbackNumber) {
       send(to, String.format(
           "Got it, %s. We'll call you shortly on *%s*. " +
           "Please keep your phone nearby.",
           firstName, callbackNumber
       ));
   }

   /**
    * Sends a callback alert to a founder (Nziima or Ntobeko).
    * Both founders receive this notification so either can call the customer.
    */
   public void sendCallbackAlert(String founderNumber, String customerName,
                                  String callbackNumber, String customerWhatsApp) {
       send(founderNumber, String.format(
           "📞 *CALLBACK REQUIRED*\n\n" +
           "Customer: *%s*\n" +
           "Call them on: *%s*\n" +
           "Their WhatsApp: %s\n\n" +
           "Please call as soon as possible.",
           customerName, callbackNumber, customerWhatsApp
       ));
   }
}
