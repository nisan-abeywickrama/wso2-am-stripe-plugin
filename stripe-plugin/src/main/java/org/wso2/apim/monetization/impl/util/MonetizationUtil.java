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
package org.wso2.apim.monetization.impl.util;

import com.google.gson.Gson;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.json.simple.JSONObject;
import org.wso2.apim.monetization.impl.StripeMonetizationConstants;
import org.wso2.apim.monetization.impl.StripeMonetizationException;
import org.wso2.carbon.apimgt.api.APIManagementException;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;
import org.wso2.carbon.apimgt.impl.utils.APIUtil;
import org.wso2.carbon.apimgt.persistence.APIPersistence;
import org.wso2.carbon.apimgt.persistence.PersistenceManager;
import org.wso2.carbon.apimgt.persistence.dto.Organization;
import org.wso2.carbon.apimgt.persistence.dto.PublisherAPI;
import org.wso2.carbon.apimgt.persistence.dto.PublisherAPIProduct;
import org.wso2.carbon.apimgt.persistence.exceptions.APIPersistenceException;

import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public final class MonetizationUtil {

    private static APIManagerConfiguration config;
    private static volatile APIPersistence apiPersistenceInstance;

    public static void setProxy() {
        if (Stripe.getConnectionProxy() == null) {
            if (config == null) {
                config = getConfig();
            }
            String proxyEnabled = config.getFirstProperty(APIConstants.PROXY_ENABLE);
            //proxy enabled under monetization configs
            String proxyEnabledForMonetization = config.getFirstProperty(
                    StripeMonetizationConstants.MONETIZATION_PROXY_ENABLE_CONFIG);

            if (Boolean.parseBoolean(proxyEnabled) && Boolean.parseBoolean(proxyEnabledForMonetization)) {
                String proxyHost = config.getFirstProperty(APIConstants.PROXY_HOST);
                String proxyPort = config.getFirstProperty(APIConstants.PROXY_PORT);
                String proxyUsername = config.getFirstProperty(APIConstants.PROXY_USERNAME);
                String proxyPassword = config.getFirstProperty(APIConstants.PROXY_PASSWORD);

                InetSocketAddress inetSocketAddress = new InetSocketAddress(proxyHost,
                        Integer.parseInt(proxyPort));
                Proxy proxy = new Proxy(Proxy.Type.HTTP, inetSocketAddress);
                Stripe.setConnectionProxy(proxy);
                //set proxy auth
                if (proxyUsername != null && proxyPassword != null) {
                    PasswordAuthentication auth = new PasswordAuthentication(proxyUsername,
                            proxyPassword.toCharArray());
                    Stripe.setProxyCredential(auth);
                }
            }
        }
    }

    public static APIManagerConfiguration getConfig() {
        APIManagerConfiguration configuration = ServiceReferenceHolder.getInstance().
                getAPIManagerConfigurationService().getAPIManagerConfiguration();
        return configuration;
    }

    /**
     * Verifies that a Stripe Checkout session is fully paid by querying the Stripe API directly.
     * Loads credentials from tenant config and the API's (or API Product's) monetization properties,
     * then asserts {@code status == "complete"} and
     * {@code paymentStatus in {"paid", "no_payment_required"}}.
     *
     * @param sessionId    Stripe Checkout session ID (cs_xxx)
     * @param tenantId     APIM tenant ID of the subscriber's tenant
     * @param apiUuid      UUID of the subscribed API or API Product
     * @param tenantDomain Tenant domain derived from tenantId
     * @throws StripeMonetizationException if the session is not paid, or if credential/retrieval fails
     */
    public static void requireCheckoutSessionPaid(String sessionId, int tenantId, String apiUuid,
            String tenantDomain) throws StripeMonetizationException {

        setProxy();

        String platformKey;
        try {
            JSONObject tenantConfig = APIUtil.getTenantConfig(tenantDomain);
            if (tenantConfig == null) {
                throw new StripeMonetizationException(
                        "Tenant configuration not found for tenant: " + tenantDomain);
            }
            if (!tenantConfig.containsKey(StripeMonetizationConstants.MONETIZATION_INFO)) {
                throw new StripeMonetizationException(
                        "MonetizationInfo not configured for tenant: " + tenantDomain);
            }
            JSONObject monetizationInfo = (JSONObject) tenantConfig
                    .get(StripeMonetizationConstants.MONETIZATION_INFO);
            if (!monetizationInfo.containsKey(
                    StripeMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY)) {
                throw new StripeMonetizationException(
                        "Stripe platform account key not configured for tenant: " + tenantDomain);
            }
            platformKey = monetizationInfo.get(
                    StripeMonetizationConstants.BILLING_ENGINE_PLATFORM_ACCOUNT_KEY).toString();
            if (StringUtils.isBlank(platformKey)) {
                throw new StripeMonetizationException(
                        "Stripe platform account key is empty for tenant: " + tenantDomain);
            }
        } catch (APIManagementException e) {
            throw new StripeMonetizationException(
                    "Failed to load tenant configuration for: " + tenantDomain, e);
        }

        Organization org = new Organization(tenantDomain);
        APIPersistence persistence = getPersistenceInstance();
        String connectedAccountKey = null;

        try {
            PublisherAPI publisherAPI = persistence.getPublisherAPI(org, apiUuid);
            if (publisherAPI != null) {
                Map<String, String> props = new Gson().fromJson(
                        publisherAPI.getMonetizationProperties().toString(), HashMap.class);
                if (MapUtils.isNotEmpty(props) && props.containsKey(
                        StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
                    connectedAccountKey = props.get(
                            StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
                }
            }
        } catch (APIPersistenceException e) {
            throw new StripeMonetizationException(
                    "Failed to load API for UUID: " + apiUuid, e);
        }

        if (StringUtils.isBlank(connectedAccountKey)) {
            try {
                PublisherAPIProduct product = persistence.getPublisherAPIProduct(org, apiUuid);
                if (product != null) {
                    Map<String, String> props = product.getMonetizationProperties();
                    if (MapUtils.isNotEmpty(props) && props.containsKey(
                            StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY)) {
                        connectedAccountKey = props.get(
                                StripeMonetizationConstants.BILLING_ENGINE_CONNECTED_ACCOUNT_KEY);
                    }
                }
            } catch (APIPersistenceException e) {
                throw new StripeMonetizationException(
                        "Failed to load API or API Product for UUID: " + apiUuid, e);
            }
        }

        if (StringUtils.isBlank(connectedAccountKey)) {
            throw new StripeMonetizationException(
                    "Connected account key not found for API/Product UUID: " + apiUuid);
        }

        RequestOptions requestOptions = RequestOptions.builder()
                .setApiKey(platformKey)
                .setStripeAccount(connectedAccountKey)
                .build();

        Session session;
        try {
            session = Session.retrieve(sessionId, requestOptions);
        } catch (StripeException e) {
            throw new StripeMonetizationException(
                    "Failed to retrieve Stripe Checkout session: " + sessionId, e);
        }

        String stripeStatus = session.getStatus();
        String paymentStatus = session.getPaymentStatus();

        if (!"complete".equals(stripeStatus)) {
            throw new StripeMonetizationException(
                    "Stripe Checkout session is not yet complete (status=" + stripeStatus
                            + "): " + sessionId);
        }
        if (!"paid".equals(paymentStatus) && !"no_payment_required".equals(paymentStatus)) {
            throw new StripeMonetizationException(
                    "Stripe Checkout session payment not confirmed (paymentStatus=" + paymentStatus
                            + "): " + sessionId);
        }
    }

    private static APIPersistence getPersistenceInstance() {
        if (apiPersistenceInstance == null) {
            synchronized (MonetizationUtil.class) {
                if (apiPersistenceInstance == null) {
                    Properties properties = new Properties();
                    properties.put(APIConstants.ALLOW_MULTIPLE_STATUS,
                            APIUtil.isAllowDisplayAPIsWithMultipleStatus());
                    properties.put(APIConstants.ALLOW_MULTIPLE_VERSIONS,
                            APIUtil.isAllowDisplayMultipleVersions());
                    Map<String, String> configMap = new HashMap<>();
                    Map<String, String> configs = APIManagerConfiguration.getPersistenceProperties();
                    if (configs != null && !configs.isEmpty()) {
                        configMap.putAll(configs);
                    }
                    configMap.put(APIConstants.ALLOW_MULTIPLE_STATUS,
                            Boolean.toString(APIUtil.isAllowDisplayAPIsWithMultipleStatus()));
                    apiPersistenceInstance = PersistenceManager.getPersistenceInstance(configMap,
                            properties);
                }
            }
        }
        return apiPersistenceInstance;
    }

}
