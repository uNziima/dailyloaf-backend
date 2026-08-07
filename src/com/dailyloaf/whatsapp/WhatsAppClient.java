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
            System.err.println("[WhatsApp] Network error sending to " + to +
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

    public void sendReturningCustomerHelp(String to, String firstName) {
        send(to, String.format("""
                               Hey %s! To order just reply with the quantity of bread and day.
                               Example: '2 white monday' or '1 white 1 brown friday'
                               Or reply 'same' to repeat your last order.""",
            firstName
        ));
    }
    
/**
 * Sent to a brand new customer when they first message the business.
 * Includes the Google Form link for registration.
 */
public void sendNewCustomerGreeting(String to, String formLink) {
    send(to,
        "👋 Welcome to *Daily Loaf!*\n\n" +
        "We deliver fresh Albany bread to your door in Madadeni.\n" +
        "📅 Monday | Wednesday | Friday\n" +
        "⏰ Between 07:30 - 10:30\n" +
        "🚚 Free delivery | Minimum 2 loaves\n\n" +
        "To place your first order, fill in this quick form:\n" +
        "👉 " + formLink + "\n\n" +
        "_Takes less than a minute to complete._"
    );
}

/**
 * Fires after a returning customer selects PayShap as their payment method.
 * Includes all payment details the customer needs to pay immediately.
 */
public void sendPaymentRequest(String to, String firstName,
                               int white, int brown,
                               int total, int amount,
                               String orderId) {
    send(to, String.format(
        "Hi %s 👋\n\n" +
        "*Order Confirmed -> Payment Needed*\n" +
        "━━━━━━━━━━━━━━━━━━━━━━\n" +
        "🍞 %d white + %d brown = *%d loaves*\n" +
        "💰 Amount: *R%d*\n\n" +
        "*Pay via Card Payment / PayShap:*\n" +
        "🏦 Capitec Account: *1055617264*\n" +
        "📋 Reference: *%s*\n\n" +
        "Once we see your payment, you're confirmed ✅",
        firstName, white, brown, total, amount, orderId
    ));
}

/**
 * Sent immediately when Nziima taps Payment Received in the Delivery OS.
 * No reply needed — customer is just being informed.
 */
public void sendPaymentConfirmed(String to, String firstName,
                                 String deliveryDay) {
    send(to, String.format(
        "✅ *Payment Received, %s!*\n\n" +
        "Your order is confirmed for *%s* morning.\n" +
        "We'll be there between 07:30 – 10:30 🚪\n\n" +
        "_No reply needed_",
        firstName, deliveryDay
    ));
}

/**
 * Sent when the customer's order falls below the 2-loaf minimum.
 */
public void sendMinimumOrderNotice(String to, String firstName) {
    send(to, String.format(
        "⚠️ *Minimum Order Not Met*\n\n" +
        "Hi %s, our minimum is *2 loaves* per order.\n\n" +
        "Please reply with your updated quantity:\n" +
        "_Example: 2 white friday_",
        firstName
    ));
}

/**
 * Sent when a customer orders after the 8pm cut-off.
 * Offers to lock in the next available slot instead.
 */
public void sendCutOffNotice(String to, String firstName,
                              String requestedDay, String nextSlot) {
    send(to, String.format(
        "⏰ *Orders Closed for %s*\n\n" +
        "Hi %s, the cut-off for %s was 8pm last night.\n\n" +
        "📅 Next available slot: *%s*\n\n" +
        "Want me to lock that in for you? Reply *YES* to confirm.",
        requestedDay, firstName, requestedDay, nextSlot
    ));
}

/**
 * Sent automatically at 8pm the evening before each delivery day.
 * Reminds the customer their bread is coming tomorrow.
 * No reply needed.
 */
public void sendEveningReminder(String to, String firstName,
                                int totalLoaves) {
    send(to, String.format(
        "🌙 *Delivery Tomorrow, %s!*\n\n" +
        "Your *%d loaf%s* will be delivered in the morning.\n\n" +
        "⏰ We'll arrive between 07:30 – 10:30\n" +
        "📍 We'll message you when we're outside your gate\n\n" +
        "_No reply needed_",
        firstName, totalLoaves, totalLoaves == 1 ? "" : "s"
    ));
}

/**
 * Broadcast sent when Nziima taps "Start Deliveries" in the Delivery OS.
 * Fires to every customer with a PAID order for that day.
 * No reply needed.
 */
public void sendOnTheWay(String to, String firstName,
                          String deliveryDay) {
    send(to, String.format(
        "🚗 *Your bread is on the way, %s!*\n\n" +
        "We're heading out now and will be there shortly.\n" +
        "Please ensure your cellphone is switched on 📱\n\n" +
        "We'll message you when we're outside your gate 🚪\n\n" +
        "_No reply needed_",
        firstName
    ));
}

/**
 * Sent when the driver taps "We're Outside" on the Delivery OS.
 * Customer knows to come out immediately.
 */
public void sendWeAreOutside(String to, String firstName) {
    send(to, String.format(
        "📍 *We're Outside, %s!*\n\n" +
        "We're at your gate with your bread 🍞\n" +
        "Come collect when you're ready.",
        firstName
    ));
}

/**
 * Sent automatically when driver taps Delivered in the Delivery OS.
 * Includes referral nudge and callback hint.
 * No reply needed.
 */
public void sendPostDelivery(String to, String firstName) {
    send(to, String.format(
        "✅ *Delivered!*\n\n" +
        "Hope the bread is fresh, %s 🍞\n\n" +
        "If your neighbour wants this, send them our way.\n\n" +
        "💡 Need help anytime? Send us a *please call me* text here on WhatsApp and we'll call you back\n\n" +
        "_No reply needed_",
        firstName
    ));
}

/**
 * Sent when the driver cannot locate the customer's address.
 * Includes business number for the customer to call.
 */
public void sendCannotFindAddress(String to, String firstName,
                                  String businessNumber) {
    send(to, String.format(
        "📍 *We Can't Find You, %s*\n\n" +
        "We're nearby but can't locate your place.\n\n" +
        "Please call us on *%s*, we're only 2 minutes away 📞",
        firstName, businessNumber
    ));
}

/**
 * Sent when bread is left with a neighbour because customer was not home.
 * No reply needed.
 */
public void sendLeftWithNeighbour(String to, String firstName,
                                   String neighbourName) {
    send(to, String.format(
        "🏠 *Left With Neighbour*\n\n" +
        "Hi %s, you weren't home so we left your bread with *%s* next door.\n\n" +
        "_No reply needed_",
        firstName, neighbourName
    ));
}

/**
 * Sent after payment confirmation to capture the customer's GPS location.
 * Only needs to be done once — coordinates saved permanently.
 */
public void sendLocationInstruction(String to, String firstName) {
    send(to, String.format(
        "📍 *One Last Thing, %s*\n\n" +
        "To make sure we find your door on delivery day, " +
        "please share your location:\n\n" +
        "Tap 📎 → *Location* → *Send Your Current Location*\n\n" +
        "_This only needs to be done once and we'll save it permanently._",
        firstName
    ));
}

/**
 * Sent after a customer successfully shares their WhatsApp location.
 * No reply needed.
 */
public void sendLocationConfirmed(String to, String firstName) {
    send(to, String.format(
        "✅ *Location Saved!*\n\n" +
        "Perfect, %s. We've got your location, " +
        "we'll find your door on every delivery day.\n\n" +
        "_No reply needed_",
        firstName
    ));
}

/**
 * Sent to new customers who have never successfully paid before.
 * Explains why upfront payment is required.
 * No reply needed.
 */
public void sendFirstTimeSecurityNotice(String to, String firstName) {
    send(to, String.format(
        "📋 *Payment Notice*\n\n" +
        "Hi %s, for new customers we require payment before delivery.\n\n" +
        "This protects both parties and ensures your bread is " +
        "reserved for you.\n\n" +
        "Once your first payment is confirmed, future orders can " +
        "be paid on delivery if you prefer 🙂\n\n" +
        "_No reply needed_",
        firstName
    ));
}

/**
 * Sent when a customer looks up their order by 7-digit reference.
 * Returns full order details and payment instructions.
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

    String statusText = switch (status != null ? status : "") {
        case "PENDING_PAYMENT" -> "⏳ Awaiting payment";
        case "PAID"            -> "✅ Payment confirmed";
        case "DELIVERED"       -> "✅ Delivered";
        case "CANCELLED"       -> "❌ Cancelled";
        default                -> status != null ? status : "Unknown";
    };

    send(to, String.format(
        "Hi %s 👋\n\n" +
        "*Order Summary: %s*\n" +
        "━━━━━━━━━━━━━━━━━━━━━━━━━━\n" +
        "🍞 %s white + %s brown loaves\n" +
        "📅 Delivery: %s\n" +
        "💰 Amount: R%s\n" +
        "💳 Payment: %s\n" +
        "📊 Status: %s\n\n" +
        "*To pay via Card Payment / PayShap:*\n" +
        "🏦 Capitec Account: *%s*\n" +
        "📋 Reference: *%s*\n\n" +
        "_Reply CASH if you prefer to pay on delivery._",
        firstName, orderId,
        white, brown,
        deliveryDate, amount, payment, statusText,
        capitecNumber, orderId
    ));
}

/**
 * Confirms to the customer that we will call them on their provided number.
 * No reply needed.
 */
public void sendCallbackConfirmation(String to, String firstName,
                                     String callbackNumber) {
    send(to, String.format(
        "📞 *We'll Call You Shortly!*\n\n" +
        "Got it, %s. We'll call you on *%s*.\n" +
        "Please keep your phone nearby 📱\n\n" +
        "_No reply needed_",
        firstName, callbackNumber
    ));
}

/**
 * Sends a callback alert to a founder listed in the CONTACTS tab.
 * Both founders receive this message when a customer requests a call.
 */
public void sendCallbackAlert(String founderNumber, String customerName,
                               String callbackNumber, String customerWA) {
    send(founderNumber, String.format(
        "📞 *CALLBACK REQUIRED*\n\n" +
        "*Customer:* %s\n" +
        "*Call on:* %s\n" +
        "*WhatsApp:* %s\n\n" +
        "Please call as soon as possible.",
        customerName, callbackNumber, customerWA
    ));
}

    /**
     * Sent to a returning customer who messages something unrecognised.
     * Asks if they are trying to place an order, yes/no response expected.
     */
    public void sendAreYouOrdering(String to, String firstName) {
        send(to, String.format(
            "Hey %s 👋\n\n" +
            "I didn't quite catch that.\n\n" +
            "*Are you trying to place an order?*\n\n" +
            "Reply *YES* to order\n" +
            "Reply *NO* for other options",
            firstName
        ));
    }

    /**
     * Sent after a returning customer replies YES to the ordering prompt.
     * Explains all the order formats clearly with examples.
     */
    public void sendOrderingInstructions(String to, String firstName) {
        send(to, String.format(
            "Got it, %s! Here's how to order \n\n" +
            "*Format:* [quantity] [colour] [day]\n\n" +
            "*Examples:*\n" +
            "• _2 white monday_\n" +
            "• _1 white 1 brown friday_\n" +
            "• _3 brown wednesday_\n" +
            "• _same_ - repeats your last order\n" +
            "• _same friday_ - repeats your last order for Friday\n\n" +
            "📅 *Days:* Monday | Wednesday | Friday\n" +
            "📦 *Minimum:* 2 loaves per order\n" +
            "⏰ *Cut-off:* 8pm the night before\n\n" +
            "What would you like?",
            firstName
        ));
    }

    /**
     * Sent when a returning customer replies NO to the ordering prompt.
     * Presents a menu of options for what they might need instead.
     */
    public void sendNoMenu(String to, String firstName) {
        send(to, String.format(
            "No problem, %s! What can we help you with?\n\n" +
            "*1️⃣* Check my order status\n" +
            "*2️⃣* Request a callback\n" +
            "*3️⃣* Report a delivery issue\n" +
            "*4️⃣* Learn about subscriptions\n" +
            "*5️⃣* Something else\n\n" +
            "Reply with the number of your choice",
            firstName
        ));
    }

    /**
     * Sent when a customer sends an invalid response to the options menu.
     * Repeats the menu so they can choose correctly.
     */
    public void sendMenuError(String to, String firstName) {
        send(to, String.format(
            "⚠️ *Please choose from the options, %s*\n\n" +
            "Reply with a number from 1 to 5:\n\n" +
            "*1️⃣* Check my order status\n" +
            "*2️⃣* Request a callback\n" +
            "*3️⃣* Report a delivery issue\n" +
            "*4️⃣* Learn about subscriptions\n" +
            "*5️⃣* Something else",
            firstName
        ));
    }

    /**
     * Sent when customer chooses option 3 — report a delivery issue.
     * Asks them to describe the problem.
     */
    public void sendDeliveryIssuePrompt(String to, String firstName) {
        send(to, String.format(
            "😔 *Sorry to hear that, %s*\n\n" +
            "Please describe the issue and we'll look into it right away.\n\n" +
            "Alternatively, call us directly on *%s*.",
            firstName, "0658374361 / 072 992 2827"
        ));
    }

    /**
     * Sent when customer chooses option 5 — something else.
     * Open-ended prompt for them to explain what they need.
     */
    public void sendSomethingElsePrompt(String to, String firstName) {
        send(to, String.format(
            "Of course, %s! Please describe what you need " +
            "and we'll get back to you as soon as possible 🙂",
            firstName
        ));
    }

    /**
     * Week 3+ subscription offer sent to eligible customers.
     */
    public void sendSubscriptionOffer(String to, String firstName,
                                      String deliveryDay) {
        send(to, String.format(
            "🔄 *Subscription Offer, %s!*\n\n" +
            "Want to make your bread delivery automatic?\n\n" +
            "Lock in a standing order every *%s* " +
            "No messages needed and the bread arrives automatically.\n\n" +
            "Reply *YES* to activate your subscription.",
            firstName, deliveryDay
        ));
    }

    /**
     * Sent to the top 3 highest-volume customers at the start of each month.
     * Recognition only — no discount. No reply needed.
     */
    public void sendTopThreeAcknowledgement(String to, String firstName) {
        send(to, String.format(
            "⭐ *Top Customer!*\n\n" +
            "Hey %s, you're one of our top 3 customers this month.\n\n" +
            "Genuinely appreciate your support 🙏\n\n" +
            "_No reply needed_",
            firstName
        ));
    }

    /**
     * Payment method prompt — shown after a returning customer places an order.
     * Customer replies 1 for PayShap or 2 for Cash.
     */
    public void sendPaymentMethodPrompt(String to, String firstName,
                                        int white, int brown,
                                        String deliveryDay, int amount) {
        send(to, String.format(
            "Got it, %s \n\n" +
            "*Order Summary*\n" +
            "━━━━━━━━━━━━━━━\n" +
            "🍞 %d white + %d brown = *%d loaves*\n" +
            "📅 %s delivery\n" +
            "💰 Total: *R%d*\n\n" +
            "*How would you like to pay?*\n\n" +
            "Reply *1* for Card Payment / PayShap\n" +
            "Reply *2* for Cash on delivery",
            firstName, white, brown, white + brown, deliveryDay, amount
        ));
    }

    /**
     * Sent when customer is asked for their callback number.
     */
    public void sendCallbackNumberPrompt(String to, String firstName) {
        send(to, String.format(
            "📞 *Request Received*\n\n" +
            "Of course, %s! Please type and send your cellphone number " +
            "and we'll call you back shortly.\n\n" +
            "_Example: *0821234567*_",
            firstName
        ));
    }
}
