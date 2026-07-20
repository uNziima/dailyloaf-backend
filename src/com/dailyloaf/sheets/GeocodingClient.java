package com.dailyloaf.sheets;

import com.dailyloaf.util.Json;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Geocodes Madadeni addresses by scanning known street patterns.
 * Madadeni A streets: Ma1 Street → Ma45 Street
 * Madadeni B streets: Mb1 Street → Mb31 Street
 *
 * Results are cached in the CUSTOMERS tab so each address
 * is only geocoded once — ever.
 */
public class GeocodingClient {

    private static final String GEOCODE_URL =
        "https://maps.googleapis.com/maps/api/geocode/json";

    // Confirmed bounding box for Madadeni A and B
    // SW: -27.7800, 29.9900  NE: -27.7300, 30.0600
    private static final double BOUND_LAT_MIN = -27.7800;
    private static final double BOUND_LAT_MAX = -27.7300;
    private static final double BOUND_LNG_MIN =  29.9900;
    private static final double BOUND_LNG_MAX =  30.0600;

    private final String     apiKey;
    private final HttpClient http;

    public GeocodingClient(String apiKey) {
        this.apiKey = apiKey;
        this.http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    // ── Public API ───────────────────────────────────────────

    /**
     * Finds the lat/lng for a house in Madadeni.
     * Scans all known street patterns for the given section.
     * Returns null if no valid location found within bounds.
     *
     * @param houseNumber Raw house number from form e.g. "A9553" or "9553"
     * @param section     Section from form e.g. "Ikwezi", "Section 1", "Section 2"
     */
    public double[] geocode(String houseNumber, String section) {
        String cleanNumber  = cleanHouseNumber(houseNumber);
        if (cleanNumber.isEmpty()) return null;

        String madadeniArea = sectionToMadadeniArea(section);
        if (madadeniArea == null) return null;

        // Attempt 1 — direct erf number search
        String directAddress = cleanNumber + " " + madadeniArea +
                               ", Newcastle, KwaZulu-Natal, South Africa";
        double[] direct = callGeocodingApi(directAddress);
        if (direct != null) {
            System.out.println("[Geocoding] Direct match: " + directAddress);
            return direct;
        }

        // Attempt 2 — scan known streets
        String[] streets = getStreets(madadeniArea);
        if (streets == null) return null;

        System.out.println("[Geocoding] Scanning " + streets.length +
                           " streets for " + cleanNumber + " in " + madadeniArea);

        for (String street : streets) {
            String address = cleanNumber + " " + street + ", " +
                             madadeniArea + ", Newcastle, " +
                             "KwaZulu-Natal, South Africa";

            double[] result = callGeocodingApi(address);
            if (result != null) {
                System.out.println("[Geocoding] Found: " + address);
                return result;
            }

            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }

        System.out.println("[Geocoding] No result for " + cleanNumber +
                           " in " + madadeniArea);
        return null;
    }

    // ── Street lists ─────────────────────────────────────────

    private String[] getStreets(String madadeniArea) {
        if ("Madadeni A".equals(madadeniArea)) {
            return buildStreets("Ma", 1, 45); // TEMP: only 3 streets for diagnosis
        }
        if ("Madadeni B".equals(madadeniArea)) {
            return buildStreets("Mb", 1, 31);
        }
        return null;
    }

    private String[] buildStreets(String prefix, int from, int to) {
        String[] streets = new String[to - from + 1];
        for (int i = from; i <= to; i++) {
            streets[i - from] = prefix + i + " Street";
        }
        return streets;
    }

    // ── Section mapping ──────────────────────────────────────

    private String sectionToMadadeniArea(String section) {
        if (section == null) return null;
        String s = section.trim().toLowerCase();
        if (s.equals("ikwezi") || s.equals("ikhwezi") || s.equals("section 1")) {
            return "Madadeni A";
        }
        if (s.equals("section 2")) {
            return "Madadeni B";
        }
        return null;
    }

    // ── Geocoding API call ───────────────────────────────────

    private double[] callGeocodingApi(String address) {
        try {
            String encodedAddress = URLEncoder.encode(address, StandardCharsets.UTF_8);
            String encodedBounds  = URLEncoder.encode(
                "-27.7800,29.9900|-27.7300,30.0600",
                StandardCharsets.UTF_8
            );

            String url = GEOCODE_URL +
                         "?address=" + encodedAddress +
                         "&bounds="  + encodedBounds +
                         "&region=za" +
                         "&key="     + apiKey;

            HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();

            HttpResponse<String> res = http.send(req,
                HttpResponse.BodyHandlers.ofString());
            
            if (res.statusCode() != 200) return null;

            String body   = res.body();
            String status = Json.getString(body, "status");
            if (!"OK".equals(status)) return null;

            Double lat = Json.getDouble(body, "lat");
            Double lng = Json.getDouble(body, "lng");

            if (lat == null || lng == null) return null;

            double latVal = lat;
            double lngVal = lng;

            // Verify result is within Madadeni bounds
            if (latVal < BOUND_LAT_MIN || latVal > BOUND_LAT_MAX ||
                lngVal < BOUND_LNG_MIN || lngVal > BOUND_LNG_MAX) {
                return null;
            }

            return new double[]{ latVal, lngVal };

        } catch (Exception e) {
            System.err.println("[Geocoding] API error: " + e.getMessage());
            return null;
        }
    }

    // ── Utilities ────────────────────────────────────────────

    /** Strips leading/trailing letters: A9553 → 9553, 9553B → 9553 */
    private String cleanHouseNumber(String raw) {
        if (raw == null) return "";
        return raw.trim()
                  .replaceAll("^[A-Za-z]+", "")
                  .replaceAll("[A-Za-z]+$", "")
                  .trim();
    }
}