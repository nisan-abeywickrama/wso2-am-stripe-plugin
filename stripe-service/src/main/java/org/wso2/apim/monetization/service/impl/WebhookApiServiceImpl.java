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
import org.wso2.apim.monetization.impl.StripeMonetizationConstants;
import org.wso2.apim.monetization.impl.StripeMonetizationDAO;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.api.model.SubscribedAPI;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.dto.SubscriptionWorkflowDTO;
import org.wso2.carbon.apimgt.impl.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.impl.notifier.events.SubscriptionEvent;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowConstants;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowExecutor;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowException;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowExecutorFactory;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowStatus;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;

import org.wso2.apim.monetization.impl.StripeMonetizationException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.ws.rs.Consumes;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;
import java.util.Map;
import org.json.simple.JSONObject;

/**
 * REST API service implementation for handling Stripe webhook event notifications.
 */
@Path("/webhook")
public class WebhookApiServiceImpl {

    private static final Log log = LogFactory.getLog(WebhookApiServiceImpl.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final long REPLAY_TOLERANCE_SECONDS = 300L;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response handleWebhook(
            String payload,
            @HeaderParam("Stripe-Signature") String sigHeader,
            @QueryParam("tenantDomain") String tenantDomainParam) {

        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.error("Failed to parse Stripe webhook payload", e);
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Invalid JSON payload\"}")
                    .build();
        }

        String tenantDomain;
        if (tenantDomainParam != null && !tenantDomainParam.trim().isEmpty()) {
            tenantDomain = tenantDomainParam.trim();
        } else {
            String metadataTenant = event.path("data").path("object")
                    .path("metadata").path("tenantDomain").asText();
            tenantDomain = (metadataTenant != null && !metadataTenant.trim().isEmpty())
                    ? metadataTenant.trim() : "carbon.super";
        }

        String webhookSecret;
        try {
            webhookSecret = getWebhookSecret(tenantDomain);
        } catch (APIManagementException e) {
            log.error("Failed to retrieve Stripe webhook secret for tenant: " + tenantDomain, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Could not load webhook configuration\"}")
                    .build();
        }

        if (webhookSecret == null || webhookSecret.isEmpty()) {
            log.error("Stripe webhook secret is not configured for tenant: " + tenantDomain);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Webhook secret not configured\"}")
                    .build();
        }

        if (!verifySignature(payload, sigHeader, webhookSecret)) {
            log.warn("Stripe webhook signature verification failed for tenant: " + tenantDomain);
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity("{\"error\":\"Invalid signature\"}")
                    .build();
        }

        String eventType = event.path("type").asText();
        JsonNode dataObject = event.path("data").path("object");

        log.info("Stripe webhook received: type=" + eventType + ", tenant=" + tenantDomain);

        if (StripeMonetizationConstants.STRIPE_WEBHOOK_EVENT_CHECKOUT_COMPLETED.equals(eventType)) {
            return handleCheckoutSessionCompleted(dataObject);
        } else if (StripeMonetizationConstants.STRIPE_WEBHOOK_EVENT_CHECKOUT_EXPIRED.equals(eventType)) {
            return handleCheckoutSessionExpired(dataObject);
        } else if (StripeMonetizationConstants.STRIPE_WEBHOOK_EVENT_INVOICE_PAYMENT_FAILED.equals(eventType)) {
            return handleInvoicePaymentFailed(dataObject);
        } else if (StripeMonetizationConstants.STRIPE_WEBHOOK_EVENT_INVOICE_PAYMENT_SUCCEEDED.equals(eventType)) {
            return handleInvoicePaymentSucceeded(dataObject);
        } else if (StripeMonetizationConstants.STRIPE_WEBHOOK_EVENT_INVOICE_PAYMENT_ACTION_REQUIRED.equals(eventType)) {
            return handleInvoicePaymentActionRequired(dataObject);
        } else if (StripeMonetizationConstants.STRIPE_WEBHOOK_EVENT_SUBSCRIPTION_DELETED.equals(eventType)) {
            return handleSubscriptionDeleted(dataObject);
        } else if (StripeMonetizationConstants.STRIPE_WEBHOOK_EVENT_SUBSCRIPTION_UPDATED.equals(eventType)) {
            return handleSubscriptionUpdated(dataObject);
        } else {
            return Response.ok("{\"received\":true}").build();
        }
    }

    private Response handleCheckoutSessionCompleted(JsonNode sessionObject) {
        String sessionId = sessionObject.path("id").asText();
        String workflowReference = sessionObject.path("metadata").path("workflowReference").asText();

        if (sessionId == null || sessionId.isEmpty() || workflowReference == null || workflowReference.isEmpty()) {
            log.error("Stripe checkout.session.completed event missing sessionId or workflowReference");
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity("{\"error\":\"Missing session id or workflow reference\"}")
                    .build();
        }

        try {
            Map<String, String> sessionRow = StripeMonetizationDAO.getInstance().getCheckoutSession(sessionId);
            String existingStatus = sessionRow.get(StripeMonetizationConstants.CHECKOUT_COL_STATUS);
            if (StripeMonetizationConstants.CHECKOUT_SESSION_STATUS_COMPLETED.equals(existingStatus)) {
                if (log.isDebugEnabled()) {
                    log.debug("Checkout session already completed, skipping: " + sessionId);
                }
                return Response.ok("{\"received\":true}").build();
            }
        } catch (StripeMonetizationException e) {
            log.error("Error checking checkout session status for session: " + sessionId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Database error\"}")
                    .build();
        }

        try {
            completeWorkflow(workflowReference);
        } catch (APIManagementException e) {
            log.error("Failed to complete workflow for reference: " + workflowReference, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Workflow completion failed\"}")
                    .build();
        }

        log.info("Checkout session completed and workflow approved: sessionId=" + sessionId
                + ", workflowRef=" + workflowReference);
        return Response.ok("{\"received\":true}").build();
    }

    /**
     * Marks the checkout session EXPIRED in the DB and rejects the pending workflow,
     * cleaning up the orphaned record before Stripe's default 24-hour expiry window.
     */
    private Response handleCheckoutSessionExpired(JsonNode sessionObject) {
        String sessionId = sessionObject.path("id").asText();
        String workflowReference = sessionObject.path("metadata").path("workflowReference").asText();

        if (sessionId == null || sessionId.isEmpty() || workflowReference == null || workflowReference.isEmpty()) {
            log.warn("checkout.session.expired event missing sessionId or workflowReference, ignoring");
            return Response.ok("{\"received\":true}").build();
        }
        try {
            StripeMonetizationDAO.getInstance().updateCheckoutSessionStatus(
                    sessionId, StripeMonetizationConstants.CHECKOUT_SESSION_STATUS_EXPIRED);
        } catch (StripeMonetizationException e) {
            log.error("DB error marking checkout session expired: " + sessionId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Database error\"}")
                    .build();
        }
        try {
            ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
            WorkflowDTO baseDTO = apiMgtDAO.retrieveWorkflow(workflowReference);
            if (baseDTO != null) {
                SubscriptionWorkflowDTO workflowDTO = new SubscriptionWorkflowDTO();
                workflowDTO.setWorkflowReference(baseDTO.getWorkflowReference());
                workflowDTO.setExternalWorkflowReference(baseDTO.getExternalWorkflowReference());
                workflowDTO.setWorkflowType(baseDTO.getWorkflowType());
                workflowDTO.setTenantDomain(baseDTO.getTenantDomain());
                workflowDTO.setTenantId(baseDTO.getTenantId());
                workflowDTO.setCallbackUrl(baseDTO.getCallbackUrl());
                workflowDTO.setMetadata(baseDTO.getMetadata());
                workflowDTO.setCreatedTime(baseDTO.getCreatedTime());
                workflowDTO.setStatus(WorkflowStatus.REJECTED);
                workflowDTO.setUpdatedTime(System.currentTimeMillis());
                WorkflowExecutorFactory.getInstance()
                        .getWorkflowExecutor(WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_CREATION)
                        .complete(workflowDTO);
            }
        } catch (APIManagementException | WorkflowException e) {
            log.error("Failed to reject workflow for expired checkout session: " + workflowReference, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Workflow rejection failed\"}")
                    .build();
        }
        log.info("Checkout session expired, pending workflow rejected: sessionId=" + sessionId
                + ", workflowRef=" + workflowReference);
        return Response.ok("{\"received\":true}").build();
    }

    private Response handleInvoicePaymentFailed(JsonNode invoiceObject) {
        String stripeSubId = extractInvoiceSubscriptionId(invoiceObject);
        if (stripeSubId == null || stripeSubId.isEmpty()) {
            log.debug("invoice.payment_failed event has no subscription field (non-subscription invoice), ignoring");
            return Response.ok("{\"received\":true}").build();
        }
        try {
            int apimSubId = StripeMonetizationDAO.getInstance().getApimSubscriptionIdByStripeSubId(stripeSubId);
            if (apimSubId < 0) {
                log.warn("No APIM subscription found for Stripe subscription: " + stripeSubId);
                return Response.ok("{\"received\":true}").build();
            }
            blockApimSubscription(apimSubId);
            log.info("APIM subscription blocked due to payment failure: apimSubId=" + apimSubId
                    + ", stripeSubId=" + stripeSubId);
        } catch (StripeMonetizationException e) {
            log.error("DB error handling invoice.payment_failed for Stripe sub: " + stripeSubId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Database error\"}")
                    .build();
        } catch (APIManagementException e) {
            log.error("Failed to block APIM subscription for Stripe sub: " + stripeSubId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Subscription block failed\"}")
                    .build();
        }
        return Response.ok("{\"received\":true}").build();
    }

    private Response handleInvoicePaymentSucceeded(JsonNode invoiceObject) {
        String stripeSubId = extractInvoiceSubscriptionId(invoiceObject);
        if (stripeSubId == null || stripeSubId.isEmpty()) {
            log.debug("invoice.payment_succeeded event has no subscription field (non-subscription invoice), ignoring");
            return Response.ok("{\"received\":true}").build();
        }
        try {
            int apimSubId = StripeMonetizationDAO.getInstance().getApimSubscriptionIdByStripeSubId(stripeSubId);
            if (apimSubId < 0) {
                log.warn("No APIM subscription found for Stripe subscription: " + stripeSubId);
                return Response.ok("{\"received\":true}").build();
            }
            // Only unblock if currently BLOCKED
            String currentStatus = ApiMgtDAO.getInstance().getSubscriptionStatusById(apimSubId);
            if ("BLOCKED".equals(currentStatus)) {
                unblockApimSubscription(apimSubId);
                log.info("APIM subscription unblocked after successful payment: apimSubId=" + apimSubId
                        + ", stripeSubId=" + stripeSubId);
            } else {
                log.info("APIM subscription already active, no status change required: apimSubId=" + apimSubId
                        + ", stripeSubId=" + stripeSubId);
            }
        } catch (StripeMonetizationException e) {
            log.error("DB error handling invoice.payment_succeeded for Stripe sub: " + stripeSubId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Database error\"}")
                    .build();
        } catch (APIManagementException e) {
            log.error("Failed to unblock APIM subscription for Stripe sub: " + stripeSubId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Subscription unblock failed\"}")
                    .build();
        }
        return Response.ok("{\"received\":true}").build();
    }

    /**
     * Fired when a recurring invoice requires customer action (e.g. SCA/3DS on renewal).
     * Blocks the APIM subscription until the customer authenticates via the hosted invoice URL.
     */
    private Response handleInvoicePaymentActionRequired(JsonNode invoiceObject) {
        String stripeSubId = extractInvoiceSubscriptionId(invoiceObject);
        if (stripeSubId == null || stripeSubId.isEmpty()) {
            log.warn("invoice.payment_action_required event missing subscription field, ignoring");
            return Response.ok("{\"received\":true}").build();
        }
        try {
            int apimSubId = StripeMonetizationDAO.getInstance().getApimSubscriptionIdByStripeSubId(stripeSubId);
            if (apimSubId < 0) {
                log.warn("No APIM subscription found for Stripe subscription: " + stripeSubId);
                return Response.ok("{\"received\":true}").build();
            }
            blockApimSubscription(apimSubId);
            log.info("APIM subscription blocked pending customer authentication (SCA): apimSubId=" + apimSubId
                    + ", stripeSubId=" + stripeSubId);
            if (log.isDebugEnabled()) {
                String hostedUrl = invoiceObject.path("hosted_invoice_url").asText();
                log.debug("SCA required for Stripe sub " + stripeSubId + ", hosted invoice: " + hostedUrl);
            }
        } catch (StripeMonetizationException e) {
            log.error("DB error handling invoice.payment_action_required for Stripe sub: " + stripeSubId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Database error\"}")
                    .build();
        } catch (APIManagementException e) {
            log.error("Failed to block APIM subscription for Stripe sub: " + stripeSubId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Subscription block failed\"}")
                    .build();
        }
        return Response.ok("{\"received\":true}").build();
    }

    private Response handleSubscriptionDeleted(JsonNode subscriptionObject) {
        String stripeSubId = subscriptionObject.path("id").asText();
        if (stripeSubId == null || stripeSubId.isEmpty()) {
            log.warn("customer.subscription.deleted event missing id field, ignoring");
            return Response.ok("{\"received\":true}").build();
        }
        try {
            StripeMonetizationDAO dao = StripeMonetizationDAO.getInstance();
            int apimSubId = dao.getApimSubscriptionIdByStripeSubId(stripeSubId);
            if (apimSubId < 0) {
                log.warn("No APIM subscription found for Stripe subscription: " + stripeSubId);
                return Response.ok("{\"received\":true}").build();
            }
            int monetizationRowId = dao.getMonetizationRowIdByStripeSubId(stripeSubId);
            if (monetizationRowId >= 0) {
                dao.removeMonetizedSubscription(monetizationRowId);
            }
            removeApimSubscription(apimSubId);
            log.info("APIM subscription and monetization record removed following Stripe cancellation:"
                    + " apimSubId=" + apimSubId + ", stripeSubId=" + stripeSubId);
        } catch (StripeMonetizationException e) {
            log.error("DB error handling customer.subscription.deleted for Stripe sub: " + stripeSubId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Database error\"}")
                    .build();
        } catch (APIManagementException e) {
            log.error("Failed to remove APIM subscription for deleted Stripe sub: " + stripeSubId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Subscription removal failed\"}")
                    .build();
        }
        return Response.ok("{\"received\":true}").build();
    }

    /**
     * Syncs APIM subscription state when Stripe transitions a subscription to past_due or unpaid.
     * Active/trialing transitions are ignored — those are handled by invoice.payment_succeeded.
     */
    private Response handleSubscriptionUpdated(JsonNode subscriptionObject) {
        String stripeSubId = subscriptionObject.path("id").asText();
        String stripeStatus = subscriptionObject.path("status").asText();

        if (stripeSubId == null || stripeSubId.isEmpty()) {
            log.warn("customer.subscription.updated event missing id field, ignoring");
            return Response.ok("{\"received\":true}").build();
        }
        if (!"past_due".equals(stripeStatus) && !"unpaid".equals(stripeStatus)) {
            return Response.ok("{\"received\":true}").build();
        }
        try {
            int apimSubId = StripeMonetizationDAO.getInstance().getApimSubscriptionIdByStripeSubId(stripeSubId);
            if (apimSubId < 0) {
                log.warn("No APIM subscription found for Stripe subscription: " + stripeSubId);
                return Response.ok("{\"received\":true}").build();
            }
            blockApimSubscription(apimSubId);
            log.info("APIM subscription blocked due to Stripe subscription status '" + stripeStatus
                    + "': apimSubId=" + apimSubId + ", stripeSubId=" + stripeSubId);
        } catch (StripeMonetizationException e) {
            log.error("DB error handling customer.subscription.updated for Stripe sub: " + stripeSubId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Database error\"}")
                    .build();
        } catch (APIManagementException e) {
            log.error("Failed to block APIM subscription for Stripe sub: " + stripeSubId, e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("{\"error\":\"Subscription block failed\"}")
                    .build();
        }
        return Response.ok("{\"received\":true}").build();
    }

    /**
     * Extracts the Stripe subscription ID from an invoice event object.
     * Supports both the legacy top-level "subscription" field (pre-2025) and the
     * nested "parent.subscription_details.subscription" field introduced in
     * Stripe API version 2026-04-22.dahlia.
     */
    private String extractInvoiceSubscriptionId(JsonNode invoiceObject) {
        // New API: parent.subscription_details.subscription
        JsonNode parent = invoiceObject.path("parent");
        if (!parent.isMissingNode() && !parent.isNull()) {
            JsonNode subDetails = parent.path("subscription_details");
            if (!subDetails.isMissingNode() && !subDetails.isNull()) {
                String id = subDetails.path("subscription").asText();
                if (id != null && !id.isEmpty() && !"null".equals(id)) {
                    return id;
                }
            }
        }
        // Legacy API: top-level subscription field
        String id = invoiceObject.path("subscription").asText();
        if ("null".equals(id)) {
            return "";
        }
        return id;
    }

    /**
     * Updates the APIM subscription status to BLOCKED in the DB and fires a gateway notification.
     */
    private void blockApimSubscription(int subscriptionId) throws APIManagementException {
        updateApimSubscriptionStatus(subscriptionId, "BLOCKED");
    }

    /**
     * Updates the APIM subscription status to UNBLOCKED in the DB and fires a gateway notification.
     */
    private void unblockApimSubscription(int subscriptionId) throws APIManagementException {
        updateApimSubscriptionStatus(subscriptionId, "UNBLOCKED");
    }

    private void removeApimSubscription(int subscriptionId) throws APIManagementException {
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        SubscribedAPI subscribedAPI = apiMgtDAO.getSubscriptionById(subscriptionId);
        if (subscribedAPI == null) {
            log.warn("No APIM subscription found with ID: " + subscriptionId + ", skipping removal");
            return;
        }
        APIIdentifier identifier = subscribedAPI.getAPIIdentifier();
        if (identifier == null) {
            log.warn("Subscription " + subscriptionId + " has no APIIdentifier (API Product?), skipping removal");
            return;
        }
        int applicationId = subscribedAPI.getApplication().getId();
        apiMgtDAO.removeSubscription(identifier, applicationId);

        // Notify the gateway so the subscription cache is invalidated immediately.
        String tenantDomain = MultitenantUtils.getTenantDomain(identifier.getProviderName());
        int tenantId = APIUtil.getTenantIdFromTenantDomain(tenantDomain);
        SubscriptionEvent event = new SubscriptionEvent(subscribedAPI.getUUID(), subscribedAPI, tenantId,
                tenantDomain);
        APIUtil.sendNotification(event, "SUBSCRIPTIONS");
    }

    private void updateApimSubscriptionStatus(int subscriptionId, String newStatus) throws APIManagementException {
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        apiMgtDAO.updateSubscriptionStatus(subscriptionId, newStatus);

        // Fire a gateway notification so the data-plane cache is updated immediately.
        // Tenant is derived from the API provider name — the thread-local Carbon context
        // in the webhook WAR is always carbon.super and must not be used here.
        SubscribedAPI subscribedAPI = apiMgtDAO.getSubscriptionById(subscriptionId);
        if (subscribedAPI != null) {
            String providerName = subscribedAPI.getAPIIdentifier() != null
                    ? subscribedAPI.getAPIIdentifier().getProviderName()
                    : subscribedAPI.getProductId().getProviderName();
            String tenantDomain = MultitenantUtils.getTenantDomain(providerName);
            int tenantId = APIUtil.getTenantIdFromTenantDomain(tenantDomain);
            subscribedAPI.setSubStatus(newStatus);
            SubscriptionEvent event = new SubscriptionEvent(subscribedAPI.getUUID(), subscribedAPI, tenantId,
                    tenantDomain);
            APIUtil.sendNotification(event, "SUBSCRIPTIONS");
        }
    }

    /**
     * Retrieves the Stripe webhook secret from the tenant's MonetizationInfo configuration.
     */
    private String getWebhookSecret(String tenantDomain) throws APIManagementException {
        JSONObject tenantConfig = APIUtil.getTenantConfig(tenantDomain);
        if (tenantConfig == null) {
            return null;
        }
        if (!tenantConfig.containsKey(StripeMonetizationConstants.MONETIZATION_INFO)) {
            return null;
        }
        JSONObject monetizationInfo =
                (JSONObject) tenantConfig.get(StripeMonetizationConstants.MONETIZATION_INFO);
        if (!monetizationInfo.containsKey(StripeMonetizationConstants.STRIPE_WEBHOOK_SECRET)) {
            return null;
        }
        return (String) monetizationInfo.get(StripeMonetizationConstants.STRIPE_WEBHOOK_SECRET);
    }

    /**
     * Verifies the Stripe-Signature header using native HMAC-SHA256.
     * Format: t=<unix_timestamp>,v1=<hex_signature>[,v1=<hex_signature>...]
     * Signed payload: <timestamp> + "." + <raw_body>
     */
    private boolean verifySignature(String payload, String sigHeader, String secret) {
        if (sigHeader == null || sigHeader.isEmpty()) {
            return false;
        }

        String timestamp = null;
        List<String> v1Signatures = new ArrayList<>();

        for (String part : sigHeader.split(",")) {
            if (part.startsWith("t=")) {
                timestamp = part.substring(2);
            } else if (part.startsWith("v1=")) {
                v1Signatures.add(part.substring(3));
            }
        }

        if (timestamp == null || v1Signatures.isEmpty()) {
            return false;
        }

        try {
            long ts = Long.parseLong(timestamp);
            long now = System.currentTimeMillis() / 1000L;
            if (Math.abs(now - ts) > REPLAY_TOLERANCE_SECONDS) {
                log.warn("Stripe webhook timestamp is outside tolerance window: " + ts);
                return false;
            }
        } catch (NumberFormatException e) {
            return false;
        }

        String signedPayload = timestamp + "." + payload;
        String computedSig = hmacSha256Hex(signedPayload, secret);

        for (String candidate : v1Signatures) {
            if (constantTimeEquals(candidate, computedSig)) {
                return true;
            }
        }
        return false;
    }

    private String hmacSha256Hex(String data, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            Formatter formatter = new Formatter();
            for (byte b : hmacBytes) {
                formatter.format("%02x", b);
            }
            return formatter.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    /**
     * Retrieves the WorkflowDTO, reconstructs a SubscriptionWorkflowDTO with the subscription details,
     * marks it APPROVED, and calls the workflow executor's complete().
     * <p>
     * A plain WorkflowDTO is returned by ApiMgtDAO.retrieveWorkflow(), but
     * WorkflowUtils.sendNotificationAfterWFComplete() unconditionally casts to SubscriptionWorkflowDTO,
     * so we must reconstruct the richer DTO before handing off to the executor.
     */
    static void completeWorkflow(String workflowReference) throws APIManagementException {
        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        WorkflowDTO baseDTO = apiMgtDAO.retrieveWorkflow(workflowReference);
        if (baseDTO == null) {
            throw new APIManagementException("No workflow found for reference: " + workflowReference);
        }


        SubscriptionWorkflowDTO workflowDTO = new SubscriptionWorkflowDTO();
        workflowDTO.setWorkflowReference(baseDTO.getWorkflowReference());
        workflowDTO.setExternalWorkflowReference(baseDTO.getExternalWorkflowReference());
        workflowDTO.setWorkflowType(baseDTO.getWorkflowType());
        workflowDTO.setTenantDomain(baseDTO.getTenantDomain());
        workflowDTO.setTenantId(baseDTO.getTenantId());
        workflowDTO.setCallbackUrl(baseDTO.getCallbackUrl());
        workflowDTO.setMetadata(baseDTO.getMetadata());
        workflowDTO.setCreatedTime(baseDTO.getCreatedTime());
        workflowDTO.setStatus(WorkflowStatus.APPROVED);
        workflowDTO.setUpdatedTime(System.currentTimeMillis());

        // workflowReference holds the APIM subscription int ID (as set during checkout creation)
        try {
            int subscriptionId = Integer.parseInt(workflowReference);
            SubscribedAPI subscribedAPI = apiMgtDAO.getSubscriptionById(subscriptionId);
            if (subscribedAPI != null) {
                if (subscribedAPI.getAPIIdentifier() != null) {
                    workflowDTO.setApiName(subscribedAPI.getAPIIdentifier().getApiName());
                    workflowDTO.setApiVersion(subscribedAPI.getAPIIdentifier().getVersion());
                    workflowDTO.setApiProvider(subscribedAPI.getAPIIdentifier().getProviderName());
                }
                if (subscribedAPI.getSubscriber() != null) {
                    workflowDTO.setSubscriber(subscribedAPI.getSubscriber().getName());
                }
                if (subscribedAPI.getApplication() != null) {
                    workflowDTO.setApplicationId(subscribedAPI.getApplication().getId());
                    workflowDTO.setApplicationName(subscribedAPI.getApplication().getName());
                }
                if (subscribedAPI.getTier() != null) {
                    workflowDTO.setTierName(subscribedAPI.getTier().getName());
                }
            }
        } catch (NumberFormatException e) {
            log.warn("workflowReference is not an integer subscription ID, subscription fields will be empty: "
                    + workflowReference);
        }

        WorkflowExecutor executor;
        try {
            executor = WorkflowExecutorFactory.getInstance()
                    .getWorkflowExecutor(WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_CREATION);
            executor.complete(workflowDTO);
        } catch (WorkflowException e) {
            throw new APIManagementException("Workflow execution failed for reference: " + workflowReference, e);
        }
        if (log.isDebugEnabled()) {
            log.debug("Workflow completed for reference " + workflowReference);
        }
    }
}
