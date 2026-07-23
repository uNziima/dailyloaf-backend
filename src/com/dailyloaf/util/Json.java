
package com.dailyloaf.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 *
 * @author Ulikhaya Mazibuko
 */
public class Json {
    private static final String STRING_PATTERN =
    "\"@KEY@\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"";

    private static final String NUMBER_PATTERN =
        "\"@KEY@\"\\s*:\\s*(-?\\d+)";

    public static String getString(String json, String key) {
        if (json == null || key == null) return null;
        Pattern p = Pattern.compile(
            STRING_PATTERN.replace("@KEY@", Pattern.quote(key))
        );
        Matcher m = p.matcher(json);
        return m.find() ? unescape(m.group(1)) : null;
    }

    public static Long getLong(String json, String key) {
        if (json == null || key == null) return null;
        Pattern p = Pattern.compile(
            NUMBER_PATTERN.replace("@KEY@", Pattern.quote(key))
        );
        Matcher m = p.matcher(json);
        return m.find() ? Long.valueOf(m.group(1)) : null;
    }
        
    public static boolean hasKey(String json, String key) {
        return json != null && key != null && json.contains("\"" + key + "\"");
    }
    
    public static boolean isTextMessage(String json) {
        if (json == null) return false;
        return json.contains("\"messages\"")
            && !json.contains("\"messages\":[]")
            && json.contains("\"type\":\"text\"");
    }
    
    public static String object(String... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException(
                "Json.object() requires alternating key-value pairs."
            );
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < keyValues.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escape(keyValues[i])).append("\":");
            sb.append("\"").append(escape(keyValues[i + 1])).append("\"");
        }
        return sb.append("}").toString();
    }
    
    public static String escape(String s) {
        if (s == null) return "";
        return s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static String unescape(String s) {
        if (s == null) return null;
        return s
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n",  "\n")
            .replace("\\r",  "\r")
            .replace("\\t",  "\t");
    }
    
    /**
    * Extracts a decimal number value by key.
    * Works for: "lat": -27.7821 (unquoted decimal)
    */
   public static Double getDouble(String json, String key) {
       if (json == null || key == null) return null;
       Pattern p = Pattern.compile(
           "\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+\\.?\\d*)"
       );
       Matcher m = p.matcher(json);
       return m.find() ? Double.parseDouble(m.group(1)) : null;
   }
   
  
   
   /**
    * Extracts the sender's WhatsApp number from a webhook payload.
    * Searches specifically within the messages array to avoid
    * capturing the business phone number from the context field,
    * which appears in interactive message reply payloads.
    */
   public static String getMessageSender(String json) {
       if (json == null) return null;

       // Find the messages array first
       int messagesStart = json.indexOf("\"messages\"");
       if (messagesStart == -1) return null;

       // Find the first "from" after the messages array starts
       int fromStart = json.indexOf("\"from\"", messagesStart);
       if (fromStart == -1) return null;

       // Extract the value between the quotes after "from":
       int colonPos   = json.indexOf(":", fromStart + 6);
       int quoteOpen  = json.indexOf("\"", colonPos) + 1;
       int quoteClose = json.indexOf("\"", quoteOpen);

       if (quoteOpen <= 0 || quoteClose <= 0) return null;
       return json.substring(quoteOpen, quoteClose);
   }
}
