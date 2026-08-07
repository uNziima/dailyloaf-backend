package com.dailyloaf;

import com.dailyloaf.config.Config;
import com.dailyloaf.scheduler.ReminderScheduler;
import com.dailyloaf.server.DailyLoafServer;
import com.dailyloaf.sheets.SheetsClient;
import com.dailyloaf.whatsapp.WhatsAppClient;

public class Main {

    public static void main(String[] args) {
        System.out.println("==========================================");
        System.out.println("  DAILY LOAF — Backend Starting");
        System.out.println("==========================================");

        try {
            Config config = Config.load();
            
            SheetsClient   sheets   = new SheetsClient(config);
            WhatsAppClient whatsApp = new WhatsAppClient(config);

            ReminderScheduler scheduler = new ReminderScheduler(sheets, whatsApp);
            scheduler.start();

            DailyLoafServer server = new DailyLoafServer(config);
            server.start();

            System.out.println("Server running on port " + config.getPort());
            System.out.println("Webhook : http://localhost:" +
                               config.getPort() + "/webhook");
            System.out.println("Health  : http://localhost:" +
                               config.getPort() + "/health");
            System.out.println("==========================================");

        } catch (Exception e) {
            System.err.println("Failed to start: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
