
package com.dailyloaf.config;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

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
    try (InputStream is = new FileInputStream("config.properties")) {
        props.load(is);
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
                    " — check your config.properties file."
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


