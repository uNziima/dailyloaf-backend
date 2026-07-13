package com.dailyloaf.validation;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class OrderValidator {

    private static final int    CUT_OFF_HOUR  = 20;
    private static final int    MINIMUM_ORDER = 2;
    private static final ZoneId SAST          = ZoneId.of("Africa/Johannesburg");

    public enum Result {
        VALID,
        BELOW_MINIMUM,
        AFTER_CUTOFF,
        MISSING_DAY,
        INVALID_DAY
    }

    public static Result validate(int white, int brown, String deliveryDay) {

        if (deliveryDay == null || deliveryDay.isBlank()) {
            return Result.MISSING_DAY;
        }

        if (!isValidDeliveryDay(deliveryDay)) {
            return Result.INVALID_DAY;
        }

        if (white + brown < MINIMUM_ORDER) {
            return Result.BELOW_MINIMUM;
        }

        if (!isWithinCutOff(deliveryDay)) {
            return Result.AFTER_CUTOFF;
        }

        return Result.VALID;
    }

    public static boolean isWithinCutOff(String deliveryDay) {
        LocalDateTime now   = LocalDateTime.now(SAST);
        DayOfWeek     today = now.getDayOfWeek();
        int           hour  = now.getHour();

        return switch (deliveryDay.toLowerCase()) {

            case "monday" -> {
                if (today == DayOfWeek.MONDAY)                         yield false;
                if (today == DayOfWeek.SUNDAY && hour >= CUT_OFF_HOUR) yield false;
                yield true;
            }

            case "wednesday" -> {
                if (today == DayOfWeek.WEDNESDAY)                       yield false;
                if (today == DayOfWeek.TUESDAY && hour >= CUT_OFF_HOUR) yield false;
                yield true;
            }

            case "friday" -> {
                if (today == DayOfWeek.FRIDAY)                           yield false;
                if (today == DayOfWeek.THURSDAY && hour >= CUT_OFF_HOUR) yield false;
                yield true;
            }

            default -> false;
        };
    }

    public static boolean isValidDeliveryDay(String day) {
        return day != null && (
            day.equalsIgnoreCase("Monday")    ||
            day.equalsIgnoreCase("Wednesday") ||
            day.equalsIgnoreCase("Friday")
        );
    }

    public static String nextSlot(String deliveryDay) {
        if (deliveryDay == null) return "the next available day";
        return switch (deliveryDay.toLowerCase()) {
            case "monday"    -> "Wednesday";
            case "wednesday" -> "Friday";
            case "friday"    -> "Monday";
            default          -> "the next available day";
        };
    }
}
