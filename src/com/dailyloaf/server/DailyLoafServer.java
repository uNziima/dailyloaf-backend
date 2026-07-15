package com.dailyloaf.server;

import com.dailyloaf.config.Config;
import com.dailyloaf.handlers.WebhookHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

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