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
import org.wso2.apim.monetization.impl.StripeMonetizationDAO;
import org.wso2.apim.monetization.impl.StripeMonetizationException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/**
 * REST API service implementation for retrieving the Stripe Checkout URL.
 */
@Path("/checkout-url")
public class CheckoutUrlApiServiceImpl {

    private static final Log log = LogFactory.getLog(CheckoutUrlApiServiceImpl.class);

    @GET
    public Response getCheckoutUrl(
            @Context HttpServletRequest request,
            @QueryParam("subscriptionId") String subscriptionId) {

        if (subscriptionId == null || subscriptionId.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"subscriptionId query parameter is required\"}")
                    .build();
        }
        String subscriptionUuid = subscriptionId.trim();

        HttpSession session = request.getSession(false);
        if (session != null) {
            String currentAuthId = OidcUtil.getCommonAuthId(request);
            String storedAuthId = (String) session.getAttribute(OidcUtil.SESSION_COMMON_AUTH_ID_KEY);
            if (currentAuthId == null || (storedAuthId != null && !storedAuthId.equals(currentAuthId))) {
                session.invalidate();
                session = null;
            }
        }
        String username = (session != null)
                ? (String) session.getAttribute(OidcUtil.SESSION_USERNAME_KEY) : null;
        if (username == null) {
            try {
                if (session != null) {
                    session.invalidate();
                }
                String nonce = UUID.randomUUID().toString();
                HttpSession preAuthSession = request.getSession(true);
                preAuthSession.setAttribute(OidcUtil.SESSION_OIDC_NONCE_KEY, nonce);

                String targetPath = "/api/am/stripe/checkout-url?subscriptionId="
                        + URLEncoder.encode(subscriptionId.trim(), "UTF-8");
                String statePayload = nonce + "|" + targetPath;
                String state = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(statePayload.getBytes(StandardCharsets.UTF_8));
                String authUrl = OidcUtil.buildAuthorizationUrl(state);
                return Response.seeOther(URI.create(authUrl)).build();
            } catch (IOException e) {
                log.error("Failed to build OIDC authorization URL", e);
                return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .entity("{\"error\":\"Authentication redirect failed\"}")
                        .build();
            }
        }

        try {
            String owner = StripeMonetizationDAO.getInstance()
                    .getSubscriberNameBySubscriptionUUID(subscriptionUuid);
            if (owner == null || !owner.equals(username)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity("{\"error\":\"You are not authorized to access this subscription\"}")
                        .build();
            }
        } catch (StripeMonetizationException e) {
            log.error("Failed to verify subscription ownership for subscription UUID: " + subscriptionUuid, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Database error\"}")
                    .build();
        }

        String checkoutUrl;
        try {
            checkoutUrl = StripeMonetizationDAO.getInstance()
                    .getCheckoutUrlBySubscriptionId(subscriptionUuid);
        } catch (StripeMonetizationException e) {
            log.error("Failed to retrieve checkout URL for subscription UUID: " + subscriptionUuid, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Database error\"}")
                    .build();
        }

        if (checkoutUrl == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"No pending checkout session found for the given subscription\"}")
                    .build();
        }

        return Response.seeOther(URI.create(checkoutUrl)).build();
    }
}
