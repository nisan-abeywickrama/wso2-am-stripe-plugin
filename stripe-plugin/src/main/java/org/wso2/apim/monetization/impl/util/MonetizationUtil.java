/*
 *  Copyright (c) 2025, WSO2 LLC. (http://www.wso2.org) All Rights Reserved.
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

import com.stripe.Stripe;
import org.wso2.apim.monetization.impl.StripeMonetizationConstants;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;

import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;

public final class MonetizationUtil {

    private static APIManagerConfiguration config;

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

}
