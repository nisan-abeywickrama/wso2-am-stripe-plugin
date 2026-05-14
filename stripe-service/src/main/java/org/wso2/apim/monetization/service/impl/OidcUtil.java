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
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class OidcUtil {

    static final String SESSION_USERNAME_KEY = "stripe.oidc.username";
    static final String SESSION_COMMON_AUTH_ID_KEY = "stripe.oidc.common_auth_id";
    static final String SESSION_OIDC_NONCE_KEY = "stripe.oidc.nonce";

    private static final Log log = LogFactory.getLog(OidcUtil.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile String clientId;
    private static volatile String clientSecret;

    private OidcUtil() {
    }

    static void setClientCredentials(String newClientId, String newClientSecret) {
        clientId = newClientId;
        clientSecret = newClientSecret;
    }

    private static String getServerUrl() throws IOException {
        try {
            return APIUtil.getServerURL();
        } catch (APIManagementException e) {
            throw new IOException("Failed to determine server URL", e);
        }
    }

    private static String getClientId() {
        OidcSetupListener.ensureBootstrapped();
        return clientId;
    }

    private static String getClientSecret() {
        OidcSetupListener.ensureBootstrapped();
        return clientSecret;
    }

    private static String getRedirectUri() throws IOException {
        return getServerUrl() + "/api/am/stripe/callback";
    }

    static String getCommonAuthId(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if ("commonAuthId".equals(c.getName())) return c.getValue();
        }
        return null;
    }

    static String buildAuthorizationUrl(String state) throws IOException {
        return getServerUrl() + "/oauth2/authorize"
                + "?response_type=code"
                + "&client_id=" + URLEncoder.encode(getClientId(), "UTF-8")
                + "&redirect_uri=" + URLEncoder.encode(getRedirectUri(), "UTF-8")
                + "&scope=" + URLEncoder.encode("openid", "UTF-8")
                + "&state=" + URLEncoder.encode(state, "UTF-8");
    }

    static String exchangeCodeForUsername(String code) throws IOException {
        String tokenEndpoint = getServerUrl() + "/oauth2/token";
        String redirectUri = getRedirectUri();

        String body = "grant_type=authorization_code"
                + "&code=" + URLEncoder.encode(code, "UTF-8")
                + "&redirect_uri=" + URLEncoder.encode(redirectUri, "UTF-8");

        URL url = new URL(tokenEndpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        String credentials = Base64.getEncoder().encodeToString(
                (getClientId() + ":" + getClientSecret()).getBytes(StandardCharsets.UTF_8));

        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Basic " + credentials);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        int status = conn.getResponseCode();
        InputStream is = (status >= 200 && status < 300) ? conn.getInputStream() : conn.getErrorStream();
        String responseBody = readFully(is);

        if (status < 200 || status >= 300) {
            throw new IOException("Token endpoint returned HTTP " + status + ": " + responseBody);
        }

        JsonNode tokenJson = MAPPER.readTree(responseBody);
        String idToken = tokenJson.path("id_token").asText(null);
        if (idToken == null || idToken.isEmpty()) {
            throw new IOException("No id_token in token response");
        }

        return extractSubject(idToken);
    }

    private static String extractSubject(String idToken) throws IOException {
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            throw new IOException("Malformed id_token: expected at least 2 JWT parts");
        }
        byte[] payloadBytes = Base64.getUrlDecoder().decode(padBase64(parts[1]));
        JsonNode payload = MAPPER.readTree(payloadBytes);
        long exp = payload.path("exp").asLong(0);
        if (exp > 0 && System.currentTimeMillis() / 1000L > exp) {
            throw new IOException("id_token has expired");
        }
        String sub = payload.path("sub").asText(null);
        if (sub == null || sub.isEmpty()) {
            throw new IOException("No 'sub' claim in id_token payload: "
                    + new String(payloadBytes, StandardCharsets.UTF_8));
        }
        log.debug("Authenticated subject from id_token: " + sub);
        return sub;
    }

    private static String padBase64(String base64) {
        int mod = base64.length() % 4;
        if (mod == 2) return base64 + "==";
        if (mod == 3) return base64 + "=";
        return base64;
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
