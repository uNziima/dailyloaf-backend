package com.dailyloaf.whatsapp;

import com.dailyloaf.config.Config;
import com.dailyloaf.util.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

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
             "Total: R%d. Send to Capitec Account Number : 1055617264. " +
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

    public void sendPostDelivery(String to, String firstName) {
        send(to, String.format(
            "Hope the bread is fresh, %s. " +
            "If your neighbour wants this, send them our way.",
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
            We deliver fresh Albany bread to your door in Ikhwezi, Section 01 & Section 02 every Mon, Wed & Fri morning.
            
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
}
