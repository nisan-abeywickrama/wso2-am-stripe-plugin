package org.wso2.apim.monetization.impl.util;

import com.stripe.Stripe;
import org.wso2.apim.monetization.impl.StripeMonetizationConstants;
import org.wso2.carbon.apimgt.impl.APIConstants;
import org.wso2.carbon.apimgt.impl.APIManagerConfiguration;
import org.wso2.carbon.apimgt.impl.internal.ServiceReferenceHolder;

import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;

public final class ProxyUtil {

    private static APIManagerConfiguration config;

    public static void setProxy() {

        if (Stripe.getConnectionProxy() == null) {
            if (config == null) {
                config = ServiceReferenceHolder.getInstance().
                        getAPIManagerConfigurationService().getAPIManagerConfiguration();
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

}
