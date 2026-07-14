
package com.dailyloaf.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.io.File;

/**
 *
 * @author Ulikhaya Mazibuko
 */
public class Config {
    
    private final Properties props;
    private Config(Properties props) {
    this.props = props;
    }
    
    public static Config load() throws IOException {
        Properties props = new Properties();

        // Try config.properties first (local development)
        File configFile = new File("config.properties");
        if (configFile.exists()) {
            try (InputStream is = new FileInputStream(configFile)) {
                props.load(is);
                System.out.println("[Config] Loaded from config.properties");
            }
        } else {
            // Production — read from environment variables (Railway)
            System.out.println("[Config] No config.properties found - reading from environment variables");
            String[] keys = {
                "server.port",
                "whatsapp.token",
                "whatsapp.phone_number_id",
                "webhook.verify_token",
                "sheets.spreadsheet_id",
                "google.service_account_email",
                "google.private_key"
            };
            for (String key : keys) {
                String envKey = key.replace(".", "_").toUpperCase();
                String value  = System.getenv(envKey);
                if (value != null) {
                    props.setProperty(key, value);
                }
            }
        }

        Config config = new Config(props);
        config.validate();
        return config;
    }
    
    private void validate() {
    String[] required = {
        "server.port",
        "whatsapp.token",
        "whatsapp.phone_number_id",
        "webhook.verify_token",
        "sheets.spreadsheet_id"
    };
    
        for (String key : required) {
            if (get(key) == null || get(key).isBlank()) {
                throw new IllegalStateException(
                    "Missing required config key: " + key +
                    " - check your config.properties file."
                );
            }
        }
    }
    
    private String get(String key) {
        return props.getProperty(key);
    }

    private String get(String key, String defaultValue) {
        return props.getProperty(key, defaultValue);
    }
    
    public int getPort() {
        // Railway injects PORT - check that first
        String railwayPort = System.getenv("PORT");
        if (railwayPort != null) {
            return Integer.parseInt(railwayPort);
        }
        return Integer.parseInt(get("server.port", "8080"));
    }

    public String getWhatsAppToken() {
        return get("whatsapp.token");
    }

    public String getWhatsAppPhoneNumberId() {
        return get("whatsapp.phone_number_id");
    }

    public String getWebhookVerifyToken() {
        return get("webhook.verify_token");
    }

    public String getSheetsSpreadsheetId() {
        return get("sheets.spreadsheet_id");
    }

    public String getGoogleServiceAccountEmail() {
        return get("google.service_account_email");
    }

    public String getGooglePrivateKey() {
        return get("google.private_key");
    }
}


