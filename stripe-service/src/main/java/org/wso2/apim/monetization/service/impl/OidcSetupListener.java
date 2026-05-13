/*
 *  Copyright (c) 2026, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.wso2.apim.monetization.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Base64;

/** Lazily registers the stripe-service OAuth2 app via DCR on first OIDC request. */
class OidcSetupListener {

    private static final Log log = LogFactory.getLog(OidcSetupListener.class);

    static final String APPLICATION_NAME = "apim_stripe_service";
    static final String TENANT_DOMAIN = "carbon.super";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Object LOCK = new Object();
    private static volatile boolean initialized = false;

    private static final String SQL_FIND_CREDENTIALS =
            "SELECT CONSUMER_KEY, CONSUMER_SECRET FROM AM_SYSTEM_APPS WHERE NAME = ? AND TENANT_DOMAIN = ?";
    private static final String SQL_INSERT_CREDENTIALS =
            "INSERT INTO AM_SYSTEM_APPS (NAME, CONSUMER_KEY, CONSUMER_SECRET, TENANT_DOMAIN) VALUES (?, ?, ?, ?)";

    private OidcSetupListener() {
    }

    /**
     * Ensures OIDC client credentials are loaded, bootstrapping them on the first call.
     * Safe to call from multiple threads; uses double-checked locking.
     *
     * @throws RuntimeException if credential loading or SP registration fails
     */
    static void ensureBootstrapped() {
        if (initialized) {
            return;
        }
        synchronized (LOCK) {
            if (initialized) {
                return;
            }
            log.debug("Loading OIDC client credentials for " + APPLICATION_NAME);
            try {
                if (!loadStoredCredentials()) {
                    provisionApplication();
                }
                initialized = true;
            } catch (Exception e) {
                throw new RuntimeException("Failed to load OIDC client credentials", e);
            }
        }
    }

    private static boolean loadStoredCredentials() throws SQLException {
        try (Connection conn = APIMgtDBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_FIND_CREDENTIALS)) {
            ps.setString(1, APPLICATION_NAME);
            ps.setString(2, TENANT_DOMAIN);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String clientId = rs.getString("CONSUMER_KEY");
                    String clientSecret = rs.getString("CONSUMER_SECRET");
                    OidcUtil.setClientCredentials(clientId, clientSecret);
                    return true;
                }
            }
        }
        return false;
    }

    private static void persistCredentials(String clientId, String clientSecret) throws SQLException {
        try (Connection conn = APIMgtDBUtil.getConnection();
             PreparedStatement ps = conn.prepareStatement(SQL_INSERT_CREDENTIALS)) {
            ps.setString(1, APPLICATION_NAME);
            ps.setString(2, clientId);
            ps.setString(3, clientSecret);
            ps.setString(4, TENANT_DOMAIN);
            ps.executeUpdate();
        }
    }

    private static void provisionApplication() throws Exception {
        String adminUsername = APIUtil.getAdminUsername();
        String adminPassword = APIUtil.getAdminPassword();
        String baseUrl = APIUtil.getServerURL();

        log.debug("Registering OAuth2 application via DCR at " + baseUrl);

        String registrationResponse = registerOAuthApplication(baseUrl, adminUsername, adminPassword);
        JsonNode registrationJson = MAPPER.readTree(registrationResponse);
        String clientId = registrationJson.path("client_id").asText(null);
        String clientSecret = registrationJson.path("client_secret").asText(null);
        if (clientId == null || clientId.isEmpty() || clientSecret == null || clientSecret.isEmpty()) {
            throw new IOException("DCR response missing client_id or client_secret: " + registrationResponse);
        }
        log.debug("OAuth2 application registered, client_id=" + clientId);

        persistCredentials(clientId, clientSecret);
        OidcUtil.setClientCredentials(clientId, clientSecret);
        log.debug("Credentials persisted to AM_SYSTEM_APPS.");
    }

    private static String registerOAuthApplication(String baseUrl, String adminUsername, String adminPassword) throws IOException {
        String endpoint = baseUrl + "/api/identity/oauth2/dcr/v1.1/register";

        String redirectUri = baseUrl + "/api/am/stripe/callback";

        ObjectNode body = MAPPER.createObjectNode();
        body.put("client_name", APPLICATION_NAME);
        body.put("application_type", "web");
        ArrayNode grantTypes = body.putArray("grant_types");
        grantTypes.add("authorization_code");
        grantTypes.add("refresh_token");
        ArrayNode redirectUris = body.putArray("redirect_uris");
        redirectUris.add(redirectUri);

        return doHttpPost(endpoint, adminUsername, adminPassword,
                "application/json", MAPPER.writeValueAsString(body));
    }

    private static String doHttpPost(String endpoint, String username, String password,
            String contentType, String body) throws IOException {
        HttpURLConnection conn = openConnection(endpoint, username, password);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", contentType);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    private static HttpURLConnection openConnection(String endpoint, String username, String password)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        String credentials = Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
        conn.setRequestProperty("Authorization", "Basic " + credentials);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        String body = readFully(is);
        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + " from " + conn.getURL() + ": " + body);
        }
        return body;
    }

    private static String readFully(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

}
