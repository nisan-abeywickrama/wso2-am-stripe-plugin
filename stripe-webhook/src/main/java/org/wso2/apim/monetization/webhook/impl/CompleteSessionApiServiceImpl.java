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
import org.wso2.apim.monetization.impl.StripeMonetizationConstants;
import org.wso2.apim.monetization.impl.StripeMonetizationDAO;
import org.wso2.apim.monetization.impl.StripeMonetizationException;
import org.wso2.carbon.apimgt.api.APIManagementException;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Map;

/**
 * GET /api/am/stripe/complete-session?session_id={stripeSessionId}
 *
 * Browser-redirect path: after the subscriber completes payment on Stripe's hosted page,
 * Stripe redirects the browser to the success_url which triggers this endpoint with the
 * Stripe Checkout session ID. Must be @GET because Stripe fires a browser redirect (not POST).
 *
 * This endpoint:
 *   1. Looks up the checkout session in AM_STRIPE_CHECKOUT_SESSIONS by session_id
 *   2. Checks for idempotency (already COMPLETED → return 200)
 *   3. Retrieves the associated APIM workflow by workflowReference
 *   4. Calls WorkflowExecutorFactory.complete() to approve the subscription
 *
 * NOTE: The webhook (POST /webhook) is the primary completion path.
 * This endpoint exists as a fallback when the Stripe dashboard redirect fires before the webhook.
 */
@Path("/complete-session")
public class CompleteSessionApiServiceImpl {

    private static final Log log = LogFactory.getLog(CompleteSessionApiServiceImpl.class);

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response completeSession(@QueryParam("session_id") String sessionId) {

        if (sessionId == null || sessionId.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"session_id query parameter is required\"}")
                    .build();
        }

        Map<String, String> sessionRow;
        try {
            sessionRow = StripeMonetizationDAO.getInstance().getCheckoutSession(sessionId);
        } catch (StripeMonetizationException e) {
            log.error("Failed to retrieve checkout session for sessionId: " + sessionId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Database error\"}")
                    .build();
        }

        if (sessionRow == null || sessionRow.isEmpty()) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity("{\"error\":\"No checkout session found for the given session_id\"}")
                    .build();
        }

        String workflowReference = sessionRow.get(StripeMonetizationConstants.CHECKOUT_COL_WORKFLOW_REF);
        String status = sessionRow.get(StripeMonetizationConstants.CHECKOUT_COL_STATUS);

        if (StripeMonetizationConstants.CHECKOUT_SESSION_STATUS_COMPLETED.equals(status)) {
            return Response.ok("{\"status\":\"already_completed\"}").build();
        }

        if (workflowReference == null || workflowReference.isEmpty()) {
            log.error("Checkout session row missing workflowReference for sessionId: " + sessionId);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Missing workflow reference in checkout session\"}")
                    .build();
        }

        try {
            WebhookApiServiceImpl.completeWorkflow(workflowReference);
        } catch (APIManagementException e) {
            log.error("Failed to complete workflow for reference: " + workflowReference, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Workflow completion failed\"}")
                    .build();
        }

        return Response.ok("{\"status\":\"completed\"}").build();
    }
}
