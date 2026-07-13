package com.dailyloaf.scheduler;

import com.dailyloaf.model.Customer;
import com.dailyloaf.sheets.SheetsClient;
import com.dailyloaf.whatsapp.WhatsAppClient;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ReminderScheduler {

    private static final ZoneId SAST = ZoneId.of("Africa/Johannesburg");

    private final SheetsClient             sheets;
    private final WhatsAppClient           whatsApp;
    private final ScheduledExecutorService scheduler;

    public ReminderScheduler(SheetsClient sheets, WhatsAppClient whatsApp) {
        this.sheets    = sheets;
        this.whatsApp  = whatsApp;
        this.scheduler = Executors.newScheduledThreadPool(2);
    }

    public void start() {
        scheduleEveningReminders();
        scheduleMonthlyTopThree();
        System.out.println("[Scheduler] All jobs scheduled.");
    }

    private void scheduleEveningReminders() {
        long initialDelay = secondsUntilTime(20, 0);

        scheduler.scheduleAtFixedRate(
            this::runEveningReminderJob,
            initialDelay,
            TimeUnit.DAYS.toSeconds(1),
            TimeUnit.SECONDS
        );

        System.out.println("[Scheduler] Evening reminders: first run in " +
                           initialDelay / 3600 + " hours.");
    }

    private void runEveningReminderJob() {
        ZonedDateTime now = ZonedDateTime.now(SAST);
        DayOfWeek     day = now.getDayOfWeek();

        String deliveryDay = switch (day) {
            case SUNDAY   -> "Monday";
            case TUESDAY  -> "Wednesday";
            case THURSDAY -> "Friday";
            default       -> null;
        };

        if (deliveryDay == null) return;

        System.out.println("[Scheduler] Sending evening reminders for " +
                           deliveryDay);

        try {
            List<List<String>> paidOrders =
                sheets.getPaidOrdersForDay(deliveryDay);

            System.out.println("[Scheduler] " + paidOrders.size() +
                               " paid orders for " + deliveryDay);

            for (List<String> order : paidOrders) {
                if (order.size() < 9) continue;

                String customerId = order.get(1);
                int    white      = parseIntSafe(order.get(4));
                int    brown      = parseIntSafe(order.get(5));

                Customer customer = sheets.findCustomerByWhatsApp(customerId);
                if (customer == null) continue;

                whatsApp.sendEveningReminder(
                    customer.getWhatsappNumber(),
                    customer.getFirstName(),
                    white + brown
                );

                Thread.sleep(500);
            }

        } catch (Exception e) {
            System.err.println("[Scheduler] Evening reminder error: " +
                               e.getMessage());
        }
    }

    private void scheduleMonthlyTopThree() {
        long initialDelay = secondsUntilFirstOfMonth(8, 0);

        scheduler.scheduleAtFixedRate(
            this::runMonthlyTopThreeJob,
            initialDelay,
            TimeUnit.DAYS.toSeconds(30),
            TimeUnit.SECONDS
        );

        System.out.println("[Scheduler] Monthly Top 3: first run in " +
                           initialDelay / 3600 + " hours.");
    }

    private void runMonthlyTopThreeJob() {
        ZonedDateTime now = ZonedDateTime.now(SAST);

        if (now.getDayOfMonth() != 1) return;

        System.out.println("[Scheduler] Monthly Top 3 job running — " +
                           now.getMonth());

        System.out.println("[Scheduler] Top 3 not yet active — Stage 3+.");
    }

    private long secondsUntilTime(int hour, int minute) {
        ZonedDateTime now    = ZonedDateTime.now(SAST);
        ZonedDateTime target = now.toLocalDate()
                                 .atTime(hour, minute)
                                 .atZone(SAST);
        if (!target.isAfter(now)) {
            target = target.plusDays(1);
        }
        return Duration.between(now, target).getSeconds();
    }

    private long secondsUntilFirstOfMonth(int hour, int minute) {
        ZonedDateTime now    = ZonedDateTime.now(SAST);
        ZonedDateTime target = now.toLocalDate()
                                 .withDayOfMonth(1)
                                 .plusMonths(1)
                                 .atTime(hour, minute)
                                 .atZone(SAST);
        return Duration.between(now, target).getSeconds();
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    public void shutdown() {
        scheduler.shutdown();
        System.out.println("[Scheduler] Shut down.");
    }
}