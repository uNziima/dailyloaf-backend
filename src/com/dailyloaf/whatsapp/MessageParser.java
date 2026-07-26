package com.dailyloaf.whatsapp;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MessageParser {

    private static final Pattern WHITE_QTY =
        Pattern.compile("(\\d+)\\s*white", Pattern.CASE_INSENSITIVE);

    private static final Pattern BROWN_QTY =
        Pattern.compile("(\\d+)\\s*brown", Pattern.CASE_INSENSITIVE);

    private static final Pattern DAY =
        Pattern.compile("\\b(monday|wednesday|friday)\\b", Pattern.CASE_INSENSITIVE);

    private static final Pattern SAME =
        Pattern.compile(
            "^\\s*(same|usual)\\s*(monday|wednesday|friday)?\\s*$",
            Pattern.CASE_INSENSITIVE
        );
    // Detects exactly 7 digits — customer looking up their order reference
    // e.g. "2026006" → ORD-2026-006
    private static final Pattern ORDER_LOOKUP =
        Pattern.compile("^\\s*(\\d{7})\\s*$");

    // Detects call request variants
    private static final Pattern CALL_REQUEST =
        Pattern.compile(
            "^\\s*please\\s+call(\\s+me)?\\s*$",
            Pattern.CASE_INSENSITIVE
        );
    
    // Detects South African phone numbers in any valid format
    private static final Pattern SA_PHONE =
        Pattern.compile(
            "^\\s*(\\+27|27|0)[0-9]{8,9}\\s*$"
        );

    /**
     * Returns true if the message looks like a South African phone number.
     * Matches: 0821234567, 27821234567, +27821234567
     * Used to detect callback number replies without needing stored state.
     */
    public static boolean isPhoneNumber(String message) {
        if (message == null) return false;
        return SA_PHONE.matcher(message.trim()).matches();
    }

    public static ParsedOrder parse(String message) {
        if (message == null || message.isBlank()) return null;

        Matcher sameMatcher = SAME.matcher(message);
        if (sameMatcher.find()) {
            String day = sameMatcher.group(2);
            return new ParsedOrder(0, 0, capitalise(day), true);
        }

        int white = 0;
        int brown = 0;

        Matcher wm = WHITE_QTY.matcher(message);
        if (wm.find()) white = Integer.parseInt(wm.group(1));

        Matcher bm = BROWN_QTY.matcher(message);
        if (bm.find()) brown = Integer.parseInt(bm.group(1));

        String day = null;
        Matcher dm = DAY.matcher(message);
        if (dm.find()) day = capitalise(dm.group(1));

        if (white == 0 && brown == 0 && day == null) {
            return null;
        }

        return new ParsedOrder(white, brown, day, false);
    }
    
    /**
    * Returns true if the message is exactly 7 digits —
    * a customer looking up their order by short reference number.
    * e.g. "2026006" or "2026028"
    */
   public static boolean isOrderLookup(String message) {
       if (message == null) return false;
       return ORDER_LOOKUP.matcher(message.trim()).matches();
   }

   /**
    * Returns true if the customer is requesting a callback.
    * Matches: "please call me", "please call", case-insensitive.
    */
   public static boolean isCallRequest(String message) {
       if (message == null) return false;
       return CALL_REQUEST.matcher(message.trim()).matches();
   }

   /**
    * Converts a 7-digit short reference to a full order ID.
    * "2026006" → "ORD-2026-006"
    * "2026028" → "ORD-2026-028"
    *
    * Format: first 4 digits = year, last 3 = sequence number.
    */
   public static String toOrderId(String sevenDigits) {
       if (sevenDigits == null) return null;
       String clean = sevenDigits.trim();
       if (clean.length() != 7) return null;
       String year = clean.substring(0, 4);
       String seq  = clean.substring(4);
       return "ORD-" + year + "-" + seq;
   }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return null;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    public static class ParsedOrder {

        public final int     whiteLoaves;
        public final int     brownLoaves;
        public final String  deliveryDay;
        public final boolean isSameAsLast;

        public ParsedOrder(int white, int brown, String day, boolean sameAsLast) {
            this.whiteLoaves  = white;
            this.brownLoaves  = brown;
            this.deliveryDay  = day;
            this.isSameAsLast = sameAsLast;
        }

        public int totalLoaves() {
            return whiteLoaves + brownLoaves;
        }

        public boolean hasDay() {
            return deliveryDay != null && !deliveryDay.isBlank();
        }

        public boolean hasQuantity() {
            return whiteLoaves > 0 || brownLoaves > 0;
        }

        @Override
        public String toString() {
            if (isSameAsLast) {
                return "SAME" + (hasDay() ? " for " + deliveryDay : " — next available day");
            }
            return whiteLoaves + " white + " + brownLoaves + " brown on " + deliveryDay;
        }
    }
}
