package com.dailyloaf.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class StaticFileHandler implements HttpHandler {

    private final String baseDir;

    public StaticFileHandler(String baseDir) {
        this.baseDir = baseDir;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();

        // Strip the /delivery-os prefix to get the actual filename
        path = path.replaceFirst("^/delivery-os", "");
        if (path.isEmpty() || path.equals("/")) path = "/index.html";

        Path filePath = Paths.get(baseDir + path);

        if (!Files.exists(filePath) || Files.isDirectory(filePath)) {
            byte[] msg = "404 Not Found".getBytes();
            exchange.sendResponseHeaders(404, msg.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(msg);
            }
            return;
        }

        byte[] bytes       = Files.readAllBytes(filePath);
        String contentType = getContentType(path);

        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String getContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css"))  return "text/css";
        if (path.endsWith(".js"))   return "application/javascript";
        return "text/plain";
    }
}