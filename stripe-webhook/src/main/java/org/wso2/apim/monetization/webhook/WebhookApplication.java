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

package org.wso2.apim.monetization.webhook;

import com.fasterxml.jackson.jaxrs.json.JacksonJsonProvider;
import org.wso2.apim.monetization.webhook.impl.CheckoutUrlApiServiceImpl;
import org.wso2.apim.monetization.webhook.impl.CompleteSessionApiServiceImpl;
import org.wso2.apim.monetization.webhook.impl.PortalUrlApiServiceImpl;
import org.wso2.apim.monetization.webhook.impl.WebhookApiServiceImpl;

import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;
import java.util.HashSet;
import java.util.Set;

/**
 * JAX-RS Application class for the Stripe webhook REST API.
 * Registered via CXFNonSpringJaxrsServlet in web.xml.
 *
 * Context path (Carbon/Tomcat):
 *   WAR name  →  api#am#stripe.war
 *   URL prefix →  /api/am/stripe/
 *
 * Endpoints exposed:
 *   POST /api/am/stripe/webhook          — Stripe event push (HMAC verified)
 *   GET  /api/am/stripe/checkout-url     — Retrieve pending checkout URL by workflowReference
 *   POST /api/am/stripe/complete-session — Complete workflow after successful Stripe Checkout redirect
 */
@ApplicationPath("/")
public class WebhookApplication extends Application {

    @Override
    public Set<Object> getSingletons() {
        Set<Object> singletons = new HashSet<>();
        singletons.add(new JacksonJsonProvider());
        singletons.add(new WebhookApiServiceImpl());
        singletons.add(new CheckoutUrlApiServiceImpl());
        singletons.add(new CompleteSessionApiServiceImpl());
        singletons.add(new PortalUrlApiServiceImpl());
        return singletons;
    }
}
