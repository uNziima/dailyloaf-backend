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
