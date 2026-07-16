
package com.dailyloaf.sheets;

import com.dailyloaf.config.Config;
import com.dailyloaf.model.Customer;
import com.dailyloaf.model.OrderStatus;
import com.dailyloaf.util.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
/**
 *
 * @author Ulikhaya Mazibuko
 */
public class SheetsClient {
    private static final String TAB_CUSTOMERS = "CUSTOMERS";
    private static final String TAB_ORDERS    = "ORDERS";
    
    // CUSTOMERS tab columns (0-based)
    private static final int COL_C_ID         = 0;   // A
    private static final int COL_C_FIRST_NAME = 1;   // B
    private static final int COL_C_SURNAME    = 2;   // C
    private static final int COL_C_WHATSAPP   = 3;   // D
    private static final int COL_C_SECTION    = 4;   // E
    private static final int COL_C_HOUSE      = 5;   // F
    private static final int COL_C_STATUS     = 9;   // J

    // ORDERS tab columns (0-based)
    private static final int COL_O_ID         = 0;   // A
    private static final int COL_O_CUST_ID    = 1;   // B
    private static final int COL_O_DAY        = 3;   // D
    private static final int COL_O_WHITE_ORD  = 4;   // E
    private static final int COL_O_BROWN_ORD  = 5;   // F
    private static final int COL_O_STATUS     = 10;  // K
    private static final int COL_O_AMOUNT     = 8;   // I
    private static final int COL_O_PAYMENT    = 9;   // J
    private static final int COL_O_DEL_NOTES  = 11;  // L
    
    private static final String BASE_URL = "https://sheets.googleapis.com/v4/spreadsheets/";
    private static final int    TIMEOUT  = 15;

    private final Config           config;
    private final HttpClient       http;
    private final GoogleAuthClient auth;

    public SheetsClient(Config config) {
        this.config = config;
        this.auth   = new GoogleAuthClient(config);
        this.http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(TIMEOUT))
            .build();
    }
    
    public Customer findCustomerByWhatsApp(String whatsappNumber) {
        String range   = TAB_CUSTOMERS + "!A2:Q";
        String rawJson = getRange(range);
        if (rawJson == null) return null;

        List<List<String>> rows = parseValues(rawJson);
        for (List<String> row : rows) {
            if (row.size() > COL_C_WHATSAPP) {
                String stored = normalise(row.get(COL_C_WHATSAPP));
                if (stored.equals(normalise(whatsappNumber))) {
                    return rowToCustomer(row);
                }
            }
        }
        return null;
    }
    
    public String createCustomer(String firstName, String surname,
                                String whatsappNumber, String section,
                                String houseNumber) {
       String customerId = generateCustomerId();
       String today      = LocalDate.now().toString();

       List<String> row = new ArrayList<>();
       row.add(customerId);
       row.add(firstName);
       row.add(surname);
       row.add(whatsappNumber);
       row.add(section);
       row.add(houseNumber);
       row.add(today);
       row.add("0");        // Total Loaves Ordered
       row.add("0");        // Order Count
       row.add("Active");   // Status

       appendRow(TAB_CUSTOMERS, row);
       return customerId;
   }
    
    public String createOrder(String customerId, String deliveryDay,
                            int white, int brown, String paymentMethod,
                            OrderStatus status, String source) {
      String orderId   = generateOrderId();
      String timestamp = java.time.LocalDateTime.now().toString();
      int    amount    = (white + brown) * 20;

      List<String> row = new ArrayList<>();
      row.add(orderId);
      row.add(customerId);
      row.add(timestamp);
      row.add(deliveryDay);
      row.add(String.valueOf(white));
      row.add(String.valueOf(brown));
      row.add(String.valueOf(white));   // White to deliver (same as ordered until credit applied)
      row.add(String.valueOf(brown));   // Brown to deliver
      row.add(String.valueOf(amount));
      row.add(paymentMethod);
      row.add(status.name());
      row.add("");                      // Delivery Notes — empty at creation
      row.add(source);                  // "Form" or "WhatsApp"

      appendRow(TAB_ORDERS, row);
      return orderId;
  }
    
    public void updateOrderStatus(String orderId, OrderStatus newStatus) {
        String range   = TAB_ORDERS + "!A2:A";
        String rawJson = getRange(range);
        if (rawJson == null) return;

        List<List<String>> rows = parseValues(rawJson);
        int rowNumber = -1;

        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isEmpty() &&
                orderId.equals(rows.get(i).get(0))) {
                rowNumber = i + 2;
                break;
            }
        }

        if (rowNumber == -1) {
            System.err.println("[Sheets] Order not found: " + orderId);
            return;
        }

        String cellRange = TAB_ORDERS + "!K" + rowNumber;
        updateCell(cellRange, newStatus.name());
    }
    
    public List<List<String>> getPaidOrdersForDay(String deliveryDay) {
        String range   = TAB_ORDERS + "!A2:M";
        String rawJson = getRange(range);
        List<List<String>> result = new ArrayList<>();
        if (rawJson == null) return result;

        List<List<String>> rows = parseValues(rawJson);
        for (List<String> row : rows) {
            boolean dayMatch    = row.size() > COL_O_DAY    &&
                                  deliveryDay.equalsIgnoreCase(row.get(COL_O_DAY));
            boolean statusMatch = row.size() > COL_O_STATUS &&
                                  "PAID".equals(row.get(COL_O_STATUS));
            if (dayMatch && statusMatch) result.add(row);
        }
        return result;
    }
    
    /**
    * Returns all PAID orders for a delivery day, enriched with
    * customer details (name, section, house, WhatsApp).
    * Called by DeliveriesHandler to build the PWA delivery list.
    */
   public List<Map<String, String>> getDeliveriesForDay(String deliveryDay) {
       List<Map<String, String>> result = new ArrayList<>();

       // Step 1 — Load all customers into a lookup map
       // Key: customer ID, Value: their row data
       Map<String, List<String>> customerMap = new HashMap<>();
       String custJson = getRange(TAB_CUSTOMERS + "!A2:Q");
       if (custJson != null) {
           for (List<String> row : parseValues(custJson)) {
               if (!row.isEmpty()) {
                   customerMap.put(row.get(COL_C_ID).trim(), row);
               }
           }
       }

       // Step 2 — Fetch PAID orders for the day
       List<List<String>> orders = getPaidOrdersForDay(deliveryDay);

       // Step 3 — Enrich each order with customer details
       for (List<String> order : orders) {
           if (order.size() < 10) continue;

           String customerId = cell(order, COL_O_CUST_ID);
           List<String> customer = customerMap.get(customerId.trim());

           Map<String, String> stop = new HashMap<>();
           stop.put("orderId",       cell(order, COL_O_ID));
           stop.put("customerId",    customerId);
           stop.put("deliveryDay",   cell(order, COL_O_DAY));
           stop.put("whiteLoaves",   cell(order, COL_O_WHITE_ORD));
           stop.put("brownLoaves",   cell(order, COL_O_BROWN_ORD));
           stop.put("amount",        cell(order, COL_O_AMOUNT));
           stop.put("paymentMethod", cell(order, COL_O_PAYMENT));
           stop.put("deliveryNotes", cell(order, COL_O_DEL_NOTES));

           // Add customer details if found
           if (customer != null) {
               stop.put("firstName",   cell(customer, COL_C_FIRST_NAME));
               stop.put("surname",     cell(customer, COL_C_SURNAME));
               stop.put("whatsapp",    cell(customer, COL_C_WHATSAPP));
               stop.put("section",     cell(customer, COL_C_SECTION));
               stop.put("houseNumber", cell(customer, COL_C_HOUSE));
           } else {
               stop.put("firstName",   "Unknown");
               stop.put("surname",     "");
               stop.put("whatsapp",    "");
               stop.put("section",     "Unknown");
               stop.put("houseNumber", "");
           }

           result.add(stop);
       }

       return result;
   }
    
    private String getRange(String range) {
    try {
        String token = auth.getAccessToken();
        String url   = BASE_URL + config.getSheetsSpreadsheetId()
                     + "/values/" + encode(range);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .GET()
            .timeout(Duration.ofSeconds(TIMEOUT))
            .build();

        HttpResponse<String> res = http.send(
            req, HttpResponse.BodyHandlers.ofString()
        );

        if (res.statusCode() == 200) return res.body();
        System.err.println("[Sheets] getRange failed (" +
                           res.statusCode() + "): " + range);
        return null;

    } catch (Exception e) {
        System.err.println("[Sheets] getRange error: " + e.getMessage());
        return null;
    }
}

private void appendRow(String tab, List<String> values) {
    try {
        String token = auth.getAccessToken();
        String url   = BASE_URL + config.getSheetsSpreadsheetId()
                     + "/values/" + encode(tab + "!A1")
                     + ":append?valueInputOption=USER_ENTERED"
                     + "&insertDataOption=INSERT_ROWS";

        String body = buildValuesPayload(values);

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(TIMEOUT))
            .build();

        HttpResponse<String> res = http.send(
            req, HttpResponse.BodyHandlers.ofString()
        );

        if (res.statusCode() != 200) {
            System.err.println("[Sheets] appendRow failed (" +
                               res.statusCode() + "): " + res.body());
        }

    } catch (Exception e) {
        System.err.println("[Sheets] appendRow error: " + e.getMessage());
    }
}

private void updateCell(String cellRange, String value) {
        try {
            String token = auth.getAccessToken();
            String url   = BASE_URL + config.getSheetsSpreadsheetId()
                         + "/values/" + encode(cellRange)
                         + "?valueInputOption=USER_ENTERED";

            String body = buildValuesPayload(List.of(value));

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(TIMEOUT))
                .build();

            HttpResponse<String> res = http.send(
                req, HttpResponse.BodyHandlers.ofString()
            );

            if (res.statusCode() != 200) {
                System.err.println("[Sheets] updateCell failed (" +
                                   res.statusCode() + ")");
            }

        } catch (Exception e) {
            System.err.println("[Sheets] updateCell error: " + e.getMessage());
        }
    }

    private String generateCustomerId() {
    String range   = TAB_CUSTOMERS + "!A2:A";
    String rawJson = getRange(range);
    if (rawJson == null || !rawJson.contains("values")) return "DL-001";

    List<List<String>> rows = parseValues(rawJson);
    if (rows.isEmpty()) return "DL-001";

    String lastId  = rows.get(rows.size() - 1).get(0);
    int lastNum = Integer.parseInt(lastId.replace("DL-", "").trim());
    return "DL-" + String.format("%03d", lastNum + 1);
}

private String generateOrderId() {
        String year    = String.valueOf(LocalDate.now().getYear());
        String range   = TAB_ORDERS + "!A2:A";
        String rawJson = getRange(range);
        if (rawJson == null || !rawJson.contains("values"))
            return "ORD-" + year + "-001";

        List<List<String>> rows = parseValues(rawJson);
        if (rows.isEmpty()) return "ORD-" + year + "-001";

        String   lastId  = rows.get(rows.size() - 1).get(0);
        String[] parts   = lastId.split("-");
        int lastNum = Integer.parseInt(parts[parts.length - 1].trim());
        return "ORD-" + year + "-" + String.format("%03d", lastNum + 1);
    }

    private List<List<String>> parseValues(String json) {
        List<List<String>> result = new ArrayList<>();
        if (json == null || !json.contains("\"values\"")) return result;

        int outerStart = json.indexOf("[", json.indexOf("\"values\""));
        int outerEnd   = json.lastIndexOf("]");
        if (outerStart < 0 || outerEnd < 0) return result;

        String array = json.substring(outerStart + 1, outerEnd).trim();

        int depth = 0;
        int start = 0;
        for (int i = 0; i < array.length(); i++) {
            char c = array.charAt(i);
            if (c == '[') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    String rowStr = array.substring(start + 1, i);
                    result.add(parseRow(rowStr));
                }
            }
        }
        return result;
    }

private List<String> parseRow(String rowStr) {
        List<String> cells  = new ArrayList<>();
        boolean      inQuote = false;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < rowStr.length(); i++) {
            char c = rowStr.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ',' && !inQuote) {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().trim());
        return cells;
    }

    private String buildValuesPayload(List<String> row) {
        StringBuilder sb = new StringBuilder("{\"values\":[[");
        for (int i = 0; i < row.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(Json.escape(row.get(i))).append("\"");
        }
        sb.append("]]}" );
        return sb.toString();
    }
 
    private Customer rowToCustomer(List<String> row) {
        return new Customer(
            cell(row, COL_C_ID),
            cell(row, COL_C_FIRST_NAME),
            cell(row, COL_C_SURNAME),
            cell(row, COL_C_WHATSAPP),
            cell(row, COL_C_SECTION),
            cell(row, COL_C_HOUSE),
            cell(row, COL_C_STATUS)
        );
    }

    private String cell(List<String> row, int index) {
        return index < row.size() ? row.get(index) : "";
    }

    private String normalise(String number) {
        if (number == null) return "";
        String cleaned = number.replaceAll("[\\s\\-()+ ]", "");
        return cleaned.startsWith("0") ? "27" + cleaned.substring(1) : cleaned;
    }

    private String encode(String range) {
        return range
            .replace(" ", "%20")
            .replace("!", "%21")
            .replace(":", "%3A");
    }
    
    public void updateOrderPaymentMethod(String orderId, String paymentMethod) {
        String range   = TAB_ORDERS + "!A2:A";
        String rawJson = getRange(range);
        if (rawJson == null) return;

        List<List<String>> rows = parseValues(rawJson);
        int rowNumber = -1;

        for (int i = 0; i < rows.size(); i++) {
            if (!rows.get(i).isEmpty() &&
                orderId.equals(rows.get(i).get(0).trim())) {
                rowNumber = i + 2;
                break;
            }
        }

        if (rowNumber == -1) {
            System.err.println("[Sheets] Order not found for payment update: " + orderId);
            return;
        }

        // Column J is payment method (index 10, 1-based)
        String cellRange = TAB_ORDERS + "!J" + rowNumber;
        updateCell(cellRange, paymentMethod);
        System.out.println("[Sheets] Payment method updated: " + orderId + " → " + paymentMethod);
    }
}
