package com.dailyloaf.sheets;

import com.dailyloaf.config.Config;
import com.dailyloaf.util.Json;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

public class GoogleAuthClient {

    private static final String TOKEN_URL =
        "https://oauth2.googleapis.com/token";

    private static final String SHEETS_SCOPE =
        "https://www.googleapis.com/auth/spreadsheets";

    private String  cachedToken;
    private Instant tokenExpiry;

    private final Config     config;
    private final HttpClient http;

    public GoogleAuthClient(Config config) {
        this.config = config;
        this.http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public String getAccessToken() throws Exception {
        if (cachedToken != null &&
            Instant.now().isBefore(tokenExpiry.minusSeconds(300))) {
            return cachedToken;
        }
        return fetchNewToken();
    }

    private String fetchNewToken() throws Exception {
        String jwt      = buildSignedJwt();
        String response = postToTokenEndpoint(jwt);
        String token    = Json.getString(response, "access_token");

        if (token == null) {
            throw new RuntimeException(
                "Failed to obtain Google access token. Response: " + response
            );
        }

        cachedToken = token;
        tokenExpiry = Instant.now().plusSeconds(3600);
        System.out.println("[GoogleAuth] New access token obtained.");
        return token;
    }

    private String buildSignedJwt() throws Exception {
        long now = Instant.now().getEpochSecond();

        String header = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes()
        );

        String claimsJson = String.format(
            "{\"iss\":\"%s\",\"scope\":\"%s\",\"aud\":\"%s\",\"iat\":%d,\"exp\":%d}",
            config.getGoogleServiceAccountEmail(),
            SHEETS_SCOPE,
            TOKEN_URL,
            now,
            now + 3600
        );

        String claims = Base64.getUrlEncoder().withoutPadding().encodeToString(
            claimsJson.getBytes()
        );

        String signingInput   = header + "." + claims;
        byte[] signatureBytes = signWithRsa(signingInput);
        String signature      = Base64.getUrlEncoder().withoutPadding().encodeToString(
            signatureBytes
        );

        return signingInput + "." + signature;
    }

    private byte[] signWithRsa(String input) throws Exception {
        PrivateKey privateKey = loadPrivateKey();
        Signature  signer     = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(input.getBytes());
        return signer.sign();
    }

    private PrivateKey loadPrivateKey() throws Exception {
        String raw = config.getGooglePrivateKey()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace("\n", "")
            .replace("\r", "")
            .trim();

        byte[]              keyBytes = Base64.getDecoder().decode(raw);
        PKCS8EncodedKeySpec spec     = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory          factory  = KeyFactory.getInstance("RSA");
        return factory.generatePrivate(spec);
    }

    private String postToTokenEndpoint(String jwt) throws Exception {
        String body = "grant_type=" +
                      "urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer" +
                      "&assertion=" + jwt;

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(TOKEN_URL))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(15))
            .build();

        HttpResponse<String> response = http.send(
            request,
            HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new RuntimeException(
                "Token endpoint returned " + response.statusCode() +
                ": " + response.body()
            );
        }
        return response.body();
    }
}