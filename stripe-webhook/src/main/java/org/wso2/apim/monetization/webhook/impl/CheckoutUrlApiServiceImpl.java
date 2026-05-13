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
import org.wso2.apim.monetization.impl.StripeMonetizationDAO;
import org.wso2.apim.monetization.impl.StripeMonetizationException;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * GET /api/am/stripe/checkout-url?subscriptionId={workflowReference}
 *
 * Returns the pending Stripe Checkout URL for the given workflow reference.
 * Used by the DevPortal UI to redirect the subscriber to Stripe's hosted payment page.
 *
 * The "subscriptionId" query parameter is actually the externalWorkflowReference UUID
 * stored in AM_STRIPE_CHECKOUT_SESSIONS.WORKFLOW_REFERENCE.
 */
@Path("/checkout-url")
public class CheckoutUrlApiServiceImpl {

    private static final Log log = LogFactory.getLog(CheckoutUrlApiServiceImpl.class);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getCheckoutUrl(@QueryParam("subscriptionId") String workflowReference) {

        if (workflowReference == null || workflowReference.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"subscriptionId query parameter is required\"}")
                    .build();
        }

        String checkoutUrl;
        try {
            checkoutUrl = StripeMonetizationDAO.getInstance()
                    .getCheckoutUrlByWorkflowRef(workflowReference);
        } catch (StripeMonetizationException e) {
            log.error("Failed to retrieve checkout URL for workflowReference: " + workflowReference, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Database error\"}")
                    .build();
        }

        if (checkoutUrl == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"No pending checkout session found for the given reference\"}")
                    .build();
        }

        return Response.ok("{\"checkoutUrl\":\"" + checkoutUrl + "\"}").build();
    }
}
