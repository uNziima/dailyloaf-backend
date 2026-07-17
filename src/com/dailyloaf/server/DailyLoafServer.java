package com.dailyloaf.server;

import com.dailyloaf.config.Config;
import com.dailyloaf.handlers.BroadcastHandler;
import com.dailyloaf.handlers.SendHandler;
import com.dailyloaf.handlers.WebhookHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import com.dailyloaf.handlers.DeliveriesHandler;
import com.dailyloaf.handlers.DeliverHandler;
import com.dailyloaf.handlers.NotDeliveredHandler;
import com.dailyloaf.handlers.ConfigHandler;
import com.dailyloaf.server.StaticFileHandler;

public class DailyLoafServer {

    private final Config config;

    public DailyLoafServer(Config config) {
        this.config = config;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(
            new InetSocketAddress(config.getPort()), 0
        );

        server.createContext("/webhook", new WebhookHandler(config));
        server.createContext("/health",  this::handleHealth);
        server.createContext("/send", new SendHandler(config));
        server.createContext("/deliveries", new DeliveriesHandler(config));
        server.createContext("/deliver", new DeliverHandler(config));
        server.createContext("/not-delivered", new NotDeliveredHandler(config));
        server.createContext("/broadcast", new BroadcastHandler(config));
        server.createContext("/config", new ConfigHandler(config));
        server.createContext("/delivery-os",
            new StaticFileHandler("delivery-os"));

        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    private void handleHealth(com.sun.net.httpserver.HttpExchange exchange)
            throws IOException {
        String body  = "Daily Loaf backend is running.";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}