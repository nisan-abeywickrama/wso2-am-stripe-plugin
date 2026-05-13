/*
 *  Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.apim.monetization.impl.workflow;

import com.google.gson.Gson;
import com.stripe.exception.StripeException;
import com.stripe.model.Price;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.checkout.SessionCreateParams;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.simple.JSONObject;
import org.wso2.apim.monetization.impl.StripeMonetizationConstants;
import org.wso2.apim.monetization.impl.StripeMonetizationDAO;
import org.wso2.apim.monetization.impl.StripeMonetizationException;
import org.wso2.apim.monetization.impl.model.MonetizationSharedCustomer;
import org.wso2.apim.monetization.impl.util.MonetizationUtil;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.api.WorkflowResponse;
import org.wso2.carbon.apimgt.api.model.API;
import org.wso2.carbon.apimgt.api.model.APIIdentifier;
import org.wso2.carbon.apimgt.api.model.APIProduct;
import org.wso2.carbon.apimgt.api.model.SubscribedAPI;
import org.wso2.carbon.apimgt.api.model.Subscriber;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.dao.ApiMgtDAO;
import org.wso2.carbon.apimgt.impl.dto.SubscriptionWorkflowDTO;
import org.wso2.carbon.apimgt.impl.dto.WorkflowDTO;
import org.wso2.carbon.apimgt.impl.utils.APIMgtDBUtil;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.impl.workflow.GeneralWorkflowResponse;
import org.wso2.carbon.apimgt.impl.workflow.HttpWorkflowResponse;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowConstants;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowException;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowExecutor;
import org.wso2.carbon.apimgt.impl.workflow.WorkflowStatus;
import org.wso2.carbon.apimgt.persistence.APIPersistence;
import org.wso2.carbon.apimgt.persistence.PersistenceManager;
import org.wso2.carbon.apimgt.persistence.dto.Organization;
import org.wso2.carbon.apimgt.persistence.dto.PublisherAPI;
import org.wso2.carbon.apimgt.persistence.exceptions.APIPersistenceException;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Workflow executor for Stripe-based subscription creation using Stripe Checkout (SUBSCRIPTION mode).
 * Creates a hosted Checkout session on the API provider's connected Stripe account. On completion,
 * Stripe creates the subscription automatically; this executor records it and activates the APIM subscription.
 */
public class StripeSubscriptionCreationWorkflowExecutor extends WorkflowExecutor {

    private static final Log log = LogFactory.getLog(StripeSubscriptionCreationWorkflowExecutor.class);

    private final StripeMonetizationDAO stripeMonetizationDAO = StripeMonetizationDAO.getInstance();
    private APIPersistence apiPersistenceInstance;

    private String checkoutSuccessUrl;
    private String checkoutCancelUrl;

    public String getCheckoutSuccessUrl() {
        return checkoutSuccessUrl;
    }

    public void setCheckoutSuccessUrl(String checkoutSuccessUrl) {
        this.checkoutSuccessUrl = checkoutSuccessUrl;
    }

    public String getCheckoutCancelUrl() {
        return checkoutCancelUrl;
    }

    public void setCheckoutCancelUrl(String checkoutCancelUrl) {
        this.checkoutCancelUrl = checkoutCancelUrl;
    }

    @Override
    public String getWorkflowType() {
        return WorkflowConstants.WF_TYPE_AM_SUBSCRIPTION_CREATION;
    }

    @Override
    public List<WorkflowDTO> getWorkflowDetails(String workflowStatus) throws WorkflowException {
        return null;
    }

    @Override
    public WorkflowResponse execute(WorkflowDTO workflowDTO) throws WorkflowException {
        SubscriptionWorkflowDTO subsWorkflowDTO = (SubscriptionWorkflowDTO) workflowDTO;
        workflowDTO.setProperties("apiName", subsWorkflowDTO.getApiName());
        workflowDTO.setProperties("apiVersion", subsWorkflowDTO.getApiVersion());
        workflowDTO.setProperties("subscriber", subsWorkflowDTO.getSubscriber());
        workflowDTO.setProperties("applicationName", subsWorkflowDTO.getApplicationName());
        super.execute(workflowDTO);
        workflowDTO.setStatus(WorkflowStatus.APPROVED);
        return complete(workflowDTO);
    }

    @Override
    public WorkflowResponse monetizeSubscription(WorkflowDTO workflowDTO, API api) throws WorkflowException {
        SubscriptionWorkflowDTO subWorkFlowDTO = (SubscriptionWorkflowDTO) workflowDTO;
        Subscriber subscriber;
        try {
            subscriber = ApiMgtDAO.getInstance().getSubscriber(subWorkFlowDTO.getSubscriber());
        } catch (APIManagementException e) {
            String errorMessage = "Could not load subscriber for API subscription: " + subWorkFlowDTO.getApiName();
            log.error(errorMessage, e);
            throw new WorkflowException(errorMessage, e);
        }
        return initiateCheckoutSession(subWorkFlowDTO, subscriber, api.getUUID());
    }

    @Override
    public WorkflowResponse monetizeSubscription(WorkflowDTO workflowDTO, APIProduct apiProduct)
            throws WorkflowException {
        SubscriptionWorkflowDTO subWorkFlowDTO = (SubscriptionWorkflowDTO) workflowDTO;
        Subscriber subscriber;
        try {
            subscriber = ApiMgtDAO.getInstance().getSubscriber(subWorkFlowDTO.getSubscriber());
        } catch (APIManagementException e) {
            String errorMessage = "Could not load subscriber for API Product subscription: "
                    + subWorkFlowDTO.getApiName();
            log.error(errorMessage, e);
            throw new WorkflowException(errorMessage, e);
        }
        return initiateCheckoutSession(subWorkFlowDTO, subscriber, apiProduct.getUuid());
    }

    @Override
    public WorkflowResponse complete(WorkflowDTO workflowDTO) throws WorkflowException {
        String externalRef = workflowDTO.getExternalWorkflowReference();
        if (StringUtils.isNotBlank(externalRef)) {
            try {
                Map<String, String> sessionRow = stripeMonetizationDAO.getCheckoutSessionByWorkflowRef(externalRef);
                if (!sessionRow.isEmpty()) {
                    return completeStripeCheckoutSubscription(workflowDTO, sessionRow);
                }
            } catch (StripeMonetizationException e) {
                String errorMessage = "Failed to look up checkout session for workflowReference: " + externalRef;
                log.error(errorMessage, e);
                throw new WorkflowException(errorMessage, e);
            }
        }
        workflowDTO.setUpdatedTime(System.currentTimeMillis());
        super.complete(workflowDTO);
        try {
            ApiMgtDAO.getInstance().updateSubscriptionStatus(Integer.parseInt(workflowDTO.getWorkflowReference()),
                    APIConstants.SubscriptionStatus.UNBLOCKED);
        } catch (APIManagementException e) {
            log.error("Could not complete subscription creation workflow", e);
            throw new WorkflowException("Could not complete subscription creation workflow", e);
        }
        return new GeneralWorkflowResponse();
    }

    private String getPlatformAccountKey(int tenantId) throws WorkflowException {
        String tenantDomain = APIUtil.getTenantDomainFromTenantId(tenantId);
        try {
            JSONObject tenantConfig = APIUtil.getTenantConfig(tenantDomain);
            if (tenantConfig.containsKey(StripeMonetizationConstants.MONETIZATION_INFO)) {
                JSONObject monetizationInfo = (JSONObject) tenantConfig
                        .get(StripeMonetizationConstants.MONETIZATION_INFO);
                if (monetizationInfo.containsKey(StripeMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY)) {
                    String key = monetizationInfo
                            .get(StripeMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY).toString();
                    if (StringUtils.isBlank(key)) {
                        throw new WorkflowException(
                                "Stripe platform account key is empty for tenant: " + tenantDomain);
                    }
                    return key;
                }
            }
        } catch (APIManagementException e) {
            throw new WorkflowException(
                    "Failed to get the configuration for tenant from DB: " + tenantDomain, e);
        }
        throw new WorkflowException("Stripe platform account key not found for tenant: " + tenantDomain);
    }

    private APIPersistence getApiPersistenceInstance() {
        if (apiPersistenceInstance == null) {
            Properties properties = new Properties();
            properties.put(APIConstants.ALLOW_MULTIPLE_STATUS, APIUtil.isAllowDisplayAPIsWithMultipleStatus());
            properties.put(APIConstants.ALLOW_MULTIPLE_VERSIONS, APIUtil.isAllowDisplayMultipleVersions());
            Map<String, String> configMap = new HashMap<>();
            Map<String, String> configs = APIManagerConfiguration.getPersistenceProperties();
            if (configs != null && !configs.isEmpty()) {
                configMap.putAll(configs);
            }
            configMap.put(APIConstants.ALLOW_MULTIPLE_STATUS,
                    Boolean.toString(APIUtil.isAllowDisplayAPIsWithMultipleStatus()));
            apiPersistenceInstance = PersistenceManager.getPersistenceInstance(configMap, properties);
        }
        return apiPersistenceInstance;
    }

    private PublisherAPI loadPublisherAPI(String apiUuid, String tenantDomain) throws WorkflowException {
        Organization org = new Organization(tenantDomain);
        try {
            return getApiPersistenceInstance().getPublisherAPI(org, apiUuid);
        } catch (APIPersistenceException e) {
            String errorMessage = "Failed to retrieve API of UUID: " + apiUuid;
            log.error(errorMessage, e);
            throw new WorkflowException(errorMessage, e);
        }
    }

    private String getConnectedAccountKey(PublisherAPI publisherAPI) throws WorkflowException {
        Map<String, String> monetizationProperties = new Gson().fromJson(
                publisherAPI.getMonetizationProperties().toString(), HashMap.class);
        if (MapUtils.isNotEmpty(monetizationProperties) &&
                monetizationProperties.containsKey(
                        StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
            String key = monetizationProperties.get(
                    StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
            if (StringUtils.isBlank(key)) {
                throw new WorkflowException(
                        "Connected account key is blank for API: " + publisherAPI.getApiName());
            }
            return key;
        }
        throw new WorkflowException(
                "Connected account key not found for API: " + publisherAPI.getApiName());
    }

    /**
     * Creates a Stripe Checkout session in SUBSCRIPTION mode on the API provider's connected account.
     * The session includes the Stripe Price for the subscribed tier and allows promotion codes.
     * Persists the pending session to the database and sets the workflow status to CREATED.
     */
    private WorkflowResponse initiateCheckoutSession(SubscriptionWorkflowDTO subWorkFlowDTO,
            Subscriber subscriber, String apiUuid) throws WorkflowException {

        MonetizationUtil.setProxy();
        if (StringUtils.isBlank(checkoutSuccessUrl)) {
            throw new WorkflowException(
                    "checkoutSuccessUrl is not configured in the workflow executor configuration.");
        }
        if (StringUtils.isBlank(checkoutCancelUrl)) {
            throw new WorkflowException(
                    "checkoutCancelUrl is not configured in the workflow executor configuration.");
        }

        PublisherAPI publisherAPI = loadPublisherAPI(apiUuid, subWorkFlowDTO.getTenantDomain());
        String connectedAccountKey = getConnectedAccountKey(publisherAPI);

        SubscribedAPI subscribedAPI;
        try {
            subscribedAPI = ApiMgtDAO.getInstance()
                    .getSubscriptionById(Integer.parseInt(subWorkFlowDTO.getWorkflowReference()));
        } catch (APIManagementException e) {
            String errorMessage = "Failed to load subscription from DB for workflowReference: "
                    + subWorkFlowDTO.getWorkflowReference();
            log.error(errorMessage, e);
            throw new WorkflowException(errorMessage, e);
        }
        String tierName = subscribedAPI.getTier().getName();

        String priceId;
        try (Connection con = APIMgtDBUtil.getConnection()) {
            int apiId = ApiMgtDAO.getInstance().getAPIID(apiUuid, con);
            priceId = stripeMonetizationDAO.getBillingEnginePlanIdForTier(apiId, tierName);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to retrieve API ID for UUID: " + apiUuid;
            log.error(errorMessage, e);
            throw new WorkflowException(errorMessage, e);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to retrieve billing plan for tier: " + tierName;
            log.error(errorMessage, e);
            throw new WorkflowException(errorMessage, e);
        } catch (SQLException e) {
            String errorMessage = "Error retrieving DB connection for plan lookup";
            log.error(errorMessage, e);
            throw new WorkflowException(errorMessage, e);
        }

        String platformKey = getPlatformAccountKey(subWorkFlowDTO.getTenantId());
        RequestOptions requestOptions = RequestOptions.builder()
                .setApiKey(platformKey)
                .setStripeAccount(connectedAccountKey)
                .build();

        boolean isMetered = false;
        try {
            Price price = Price.retrieve(priceId, requestOptions);
            if (price.getRecurring() != null
                    && StripeMonetizationConstants.METERED_PLAN.equals(price.getRecurring().getUsageType())) {
                isMetered = true;
            }
        } catch (StripeException e) {
            String errorMessage = "Failed to retrieve Stripe price for plan: " + priceId;
            log.error(errorMessage, e);
            throw new WorkflowException(errorMessage, e);
        }

        SessionCreateParams.LineItem.Builder lineItemBuilder = SessionCreateParams.LineItem.builder()
                .setPrice(priceId);
        if (!isMetered) {
            lineItemBuilder.setQuantity(1L);
        }

        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl(checkoutSuccessUrl)
                .setCancelUrl(checkoutCancelUrl)
                .addLineItem(lineItemBuilder.build())
                .setAllowPromotionCodes(true)
                .setClientReferenceId(subWorkFlowDTO.getExternalWorkflowReference())
                .putMetadata("workflowReference", subWorkFlowDTO.getExternalWorkflowReference())
                .putMetadata("tenantDomain", subWorkFlowDTO.getTenantDomain())
                .build();

        try {
            Session session = Session.create(params, requestOptions);
            try {
                stripeMonetizationDAO.saveCheckoutSession(
                        session.getId(),
                        subWorkFlowDTO.getExternalWorkflowReference(),
                        subscriber.getId(),
                        subWorkFlowDTO.getTenantId(),
                        apiUuid,
                        session.getUrl());
            } catch (StripeMonetizationException e) {
                String errorMessage = "Failed to save Stripe Checkout session for workflowReference: "
                        + subWorkFlowDTO.getExternalWorkflowReference();
                log.error(errorMessage, e);
                throw new WorkflowException(errorMessage, e);
            }
            subWorkFlowDTO.setStatus(WorkflowStatus.CREATED);
            super.execute(subWorkFlowDTO);
            HttpWorkflowResponse response = new HttpWorkflowResponse();
            response.setRedirectUrl(session.getUrl());
            response.setRedirectConfirmationMsg(
                    "Please complete the checkout to activate your subscription.");
            return response;
        } catch (StripeException e) {
            String errorMessage = "Failed to create Stripe Checkout session for subscriber: "
                    + subscriber.getName();
            log.error(errorMessage, e);
            throw new WorkflowException(errorMessage, e);
        }
    }

    /**
     * Completes a subscription backed by a Stripe Checkout session in SUBSCRIPTION mode.
     * Reads the Stripe subscription ID from the completed session, records the shared customer
     * (Stripe-managed) and the subscription in the database, then activates the APIM subscription.
     */
    private WorkflowResponse completeStripeCheckoutSubscription(WorkflowDTO workflowDTO,
            Map<String, String> sessionRow) throws WorkflowException {

        String sessionId = sessionRow.get(StripeMonetizationConstants.CHECKOUT_COL_SESSION_ID);

        if (StripeMonetizationConstants.CHECKOUT_SESSION_STATUS_COMPLETED.equals(
                sessionRow.get(StripeMonetizationConstants.CHECKOUT_COL_STATUS))) {
            workflowDTO.setStatus(WorkflowStatus.APPROVED);
            workflowDTO.setUpdatedTime(System.currentTimeMillis());
            super.complete(workflowDTO);
            return new GeneralWorkflowResponse();
        }

        int subscriberId = Integer.parseInt(sessionRow.get(StripeMonetizationConstants.CHECKOUT_COL_SUBSCRIBER_ID));
        int tenantId = Integer.parseInt(sessionRow.get(StripeMonetizationConstants.CHECKOUT_COL_TENANT_ID));
        String apiUuid = sessionRow.get(StripeMonetizationConstants.CHECKOUT_COL_API_UUID);
        int subscriptionId = Integer.parseInt(workflowDTO.getWorkflowReference());

        MonetizationUtil.setProxy();
        String platformKey = getPlatformAccountKey(tenantId);

        PublisherAPI publisherAPI = loadPublisherAPI(apiUuid, workflowDTO.getTenantDomain());
        String connectedAccountKey = getConnectedAccountKey(publisherAPI);

        RequestOptions requestOptions = RequestOptions.builder()
                .setApiKey(platformKey)
                .setStripeAccount(connectedAccountKey)
                .build();

        Session session;
        try {
            session = Session.retrieve(sessionId, requestOptions);
        } catch (StripeException e) {
            String errorMessage = "Failed to retrieve Stripe Checkout session: " + sessionId;
            log.error(errorMessage, e);
            throw new WorkflowException(errorMessage, e);
        }

        String stripeSubscriptionId = session.getSubscription();
        String stripeCustomerId = session.getCustomer();
        if (StringUtils.isBlank(stripeSubscriptionId)) {
            throw new WorkflowException(
                    "Stripe Checkout session has no subscription ID — session may not be completed: " + sessionId);
        }

        ApiMgtDAO apiMgtDAO = ApiMgtDAO.getInstance();
        Subscriber subscriber;
        SubscribedAPI subscribedAPI;
        try {
            subscriber = apiMgtDAO.getSubscriber(subscriberId);
            subscribedAPI = apiMgtDAO.getSubscriptionById(subscriptionId);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to load subscriber or subscription from DB for subscriptionId: "
                    + subscriptionId;
            log.error(errorMessage, e);
            throw new WorkflowException(errorMessage, e);
        }
        int applicationId = subscribedAPI.getApplication().getId();
        String apiProvider = publisherAPI.getProviderName();
        String apiName = publisherAPI.getApiName();
        String apiVersion = publisherAPI.getVersion();

        // Ensure a shared customer record exists for this application+provider combination.
        // In SUBSCRIPTION mode, Stripe creates and owns the customer on the connected account.
        // We record the Stripe customer ID so downstream operations (e.g. usage records, cancellation) can reference it.
        MonetizationSharedCustomer sharedCustomer;
        try {
            sharedCustomer = stripeMonetizationDAO.getSharedCustomer(applicationId, apiProvider,
                    subscriber.getTenantId());
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to retrieve shared customer for applicationId: " + applicationId;
            log.error(errorMessage, e);
            throw new WorkflowException(errorMessage, e);
        }
        if (sharedCustomer.getSharedCustomerId() == null) {
            try {
                sharedCustomer.setApplicationId(applicationId);
                sharedCustomer.setApiProvider(apiProvider);
                sharedCustomer.setTenantId(subscriber.getTenantId());
                sharedCustomer.setSharedCustomerId(stripeCustomerId);
                sharedCustomer.setParentCustomerId(0);
                int id = stripeMonetizationDAO.addBESharedCustomer(sharedCustomer);
                sharedCustomer.setId(id);
            } catch (StripeMonetizationException e) {
                String errorMessage = "Failed to persist shared customer for applicationId: " + applicationId;
                log.error(errorMessage, e);
                throw new WorkflowException(errorMessage, e);
            }
        }

        APIIdentifier identifier = new APIIdentifier(apiProvider, apiName, apiVersion);
        try {
            stripeMonetizationDAO.addBESubscription(identifier, applicationId, tenantId,
                    sharedCustomer.getId(), stripeSubscriptionId, apiUuid);
        } catch (StripeMonetizationException e) {
            String errorMessage = "Failed to persist Stripe subscription for applicationId: " + applicationId;
            log.error(errorMessage, e);
            throw new WorkflowException(errorMessage, e);
        }

        try {
            apiMgtDAO.updateSubscriptionStatus(subscriptionId, APIConstants.SubscriptionStatus.UNBLOCKED);
        } catch (APIManagementException e) {
            String errorMessage = "Failed to update subscription status to UNBLOCKED for subscriptionId: "
                    + subscriptionId;
            log.error(errorMessage, e);
            throw new WorkflowException(errorMessage, e);
        }

        try {
            stripeMonetizationDAO.updateCheckoutSessionStatus(sessionId,
                    StripeMonetizationConstants.CHECKOUT_SESSION_STATUS_COMPLETED);
        } catch (StripeMonetizationException e) {
            log.error("Failed to mark checkout session as COMPLETED for sessionId: " + sessionId
                    + ". Subscription is already active.", e);
        }

        workflowDTO.setStatus(WorkflowStatus.APPROVED);
        workflowDTO.setUpdatedTime(System.currentTimeMillis());
        super.complete(workflowDTO);
        return new GeneralWorkflowResponse();
    }

}
