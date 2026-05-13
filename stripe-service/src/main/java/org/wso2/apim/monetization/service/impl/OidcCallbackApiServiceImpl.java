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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Path("/callback")
public class OidcCallbackApiServiceImpl {

    private static final Log log = LogFactory.getLog(OidcCallbackApiServiceImpl.class);

    @GET
    public Response handleCallback(
            @Context HttpServletRequest request,
            @QueryParam("code") String code,
            @QueryParam("state") String state,
            @QueryParam("error") String error,
            @QueryParam("error_description") String errorDescription) {

        if (error != null) {
            log.warn("OIDC callback returned error: " + error + " — " + errorDescription);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Authentication failed\"}")
                    .build();
        }

        if (code == null || code.isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Missing authorization code in OIDC callback\"}")
                    .build();
        }

        String targetPath;
        String stateDecoded;
        try {
            stateDecoded = new String(Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("Could not decode OIDC state parameter", e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Invalid state parameter\"}")
                    .build();
        }
        int pipeIdx = stateDecoded.indexOf('|');
        if (pipeIdx < 0) {
            log.warn("OIDC state parameter missing nonce separator");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Invalid state parameter\"}")
                    .build();
        }
        String nonceFromState = stateDecoded.substring(0, pipeIdx);
        targetPath = stateDecoded.substring(pipeIdx + 1);

        HttpSession preAuthSession = request.getSession(false);
        String storedNonce = (preAuthSession != null)
                ? (String) preAuthSession.getAttribute(OidcUtil.SESSION_OIDC_NONCE_KEY) : null;
        if (storedNonce == null || !storedNonce.equals(nonceFromState)) {
            log.warn("OIDC callback nonce mismatch. Rejecting request");
            if (preAuthSession != null) {
                preAuthSession.invalidate();
            }
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Invalid state parameter\"}")
                    .build();
        }

        String username;
        try {
            username = OidcUtil.exchangeCodeForUsername(code);
        } catch (IOException e) {
            log.error("Failed to exchange authorization code for token", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Token exchange failed\"}")
                    .build();
        }

        preAuthSession.invalidate();
        HttpSession newSession = request.getSession(true);
        newSession.setAttribute(OidcUtil.SESSION_USERNAME_KEY, username);
        String commonAuthId = OidcUtil.getCommonAuthId(request);
        if (commonAuthId != null) {
            newSession.setAttribute(OidcUtil.SESSION_COMMON_AUTH_ID_KEY, commonAuthId);
        }

        // Validate targetPath: must be a plain server-relative path to prevent open-redirect.
        if (!targetPath.startsWith("/")
                || targetPath.contains("@")
                || targetPath.contains("://")
                || targetPath.startsWith("//")) {
            log.warn("Rejecting suspicious OIDC state redirect target: " + targetPath);
            targetPath = "/devportal";
        }

        String base = request.getScheme() + "://" + request.getServerName();
        int port = request.getServerPort();
        boolean isDefaultPort = ("https".equals(request.getScheme()) && port == 443)
                || ("http".equals(request.getScheme()) && port == 80);
        if (!isDefaultPort) {
            base += ":" + port;
        }

        return Response.seeOther(URI.create(base + targetPath)).build();
    }
}
