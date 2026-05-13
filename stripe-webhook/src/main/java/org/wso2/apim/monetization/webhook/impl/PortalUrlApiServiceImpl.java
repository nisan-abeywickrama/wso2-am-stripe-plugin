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

package org.wso2.apim.monetization.webhook.impl;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.apim.monetization.impl.StripeMonetizationException;
import org.wso2.apim.monetization.impl.StripeMonetizationImpl;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * REST API service implementation for retrieving the Stripe Billing Portal URL.
 */
@Path("/portal-url")
public class PortalUrlApiServiceImpl {

    private static final Log log = LogFactory.getLog(PortalUrlApiServiceImpl.class);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getPortalUrl(
            @QueryParam("subscriptionId") String subscriptionId,
            @QueryParam("tenantDomain") String tenantDomain,
            @QueryParam("returnUrl") String returnUrl) {

        if (subscriptionId == null || subscriptionId.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"subscriptionId query parameter is required\"}")
                    .build();
        }
        if (tenantDomain == null || tenantDomain.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"tenantDomain query parameter is required\"}")
                    .build();
        }
        if (returnUrl == null || returnUrl.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"returnUrl query parameter is required\"}")
                    .build();
        }

        String portalUrl;
        try {
            portalUrl = new StripeMonetizationImpl().getBillingPortalUrl(
                    subscriptionId.trim(), tenantDomain.trim(), returnUrl.trim());
        } catch (StripeMonetizationException e) {
            log.error("Failed to create billing portal session for subscription UUID: "
                    + subscriptionId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"" + e.getMessage() + "\"}")
                    .build();
        }

        return Response.ok("{\"portalUrl\":\"" + portalUrl + "\"}").build();
    }
}
