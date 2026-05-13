# WSO2 API Manager — Stripe Monetization Extension

This repository provides a production-grade Stripe monetization integration for WSO2 API Manager 4.x. It enables API providers to charge subscribers for API access using Stripe's hosted Checkout flow, Stripe Connect, and recurring subscription billing.

The integration supports fixed-rate and metered (usage-based) billing plans, multi-tenant deployments, automatic subscription lifecycle management via Stripe webhooks, and a subscriber-facing Stripe Billing Portal for self-service payment management.

---

## Compatibility

| Extension Version | WSO2 API Manager Version |
|:-:|:-:|
| 1.0.x | 3.0.0 |
| 1.1.x | 3.1.0 – 3.2.0 |
| 1.2.x | 4.0.0 |
| 1.3.x – 1.4.x | 4.1.0 |
| **1.5.x** | **4.5.0** |

---

## Architecture Overview

The extension uses **Stripe Connect** with a platform-and-connected-account model:

- The **WSO2 tenant admin** owns the Stripe platform account. Its secret key is used to authenticate all Stripe API calls on behalf of connected accounts.
- Each **API provider** (publisher) has a Stripe connected account. Checkout sessions, subscriptions, and customers are created on this connected account, not on the platform account.
- **Subscribers** are represented as Stripe Customer objects on the API provider's connected account, stored in `AM_MONETIZATION_SHARED_CUSTOMERS`.

This separation means billing is isolated per API provider. A subscriber's payment method and invoices on one provider's Stripe account are invisible to other providers.

---

## Repository Structure

```
wso2-am-stripe-plugin/
  stripe-plugin/      OSGi bundle — core monetization logic
  stripe-webhook/     WAR — Stripe event receiver and DevPortal helper endpoints
```

The two modules are independent deployment units. The plugin runs inside the Carbon OSGi runtime; the webhook WAR runs on Carbon's embedded Tomcat with its own classloader.

---

## Components

### stripe-plugin

**Artifact:** `org.wso2.apim.monetization.impl-<version>.jar`  
**Deploy to:** `<APIM_HOME>/repository/components/dropins/`  
**Language:** Java, OSGi bundle (Apache Felix)  
**Stripe SDK:** stripe-java 24.x (bundled inside the JAR via Private-Package)

This is the primary business logic module. It implements the WSO2 `Monetization` SPI and two workflow executors.

| Class | Responsibility |
|---|---|
| `StripeMonetizationImpl` | Implements the `Monetization` interface: creates/updates/deletes Stripe Products and Plans when subscription policies are configured; records and publishes metered usage; generates the Billing Portal URL |
| `StripeMonetizationDAO` | All JDBC access to the monetization tables (`AM_MONETIZATION`, `AM_MONETIZATION_SUBSCRIPTIONS`, `AM_MONETIZATION_SHARED_CUSTOMERS`, `AM_STRIPE_CHECKOUT_SESSIONS`, `AM_POLICY_PLAN_MAPPING`) |
| `StripeMonetizationException` | Checked exception for monetization-specific errors |
| `StripeMonetizationConstants` | All SQL statements, Stripe event type strings, and configuration key constants |
| `StripeSubscriptionCreationWorkflowExecutor` | APIM workflow executor for subscription creation — creates a Stripe Checkout session on the API provider's connected account and returns a redirect URL to the subscriber |
| `StripeSubscriptionDeletionWorkflowExecutor` | APIM workflow executor for subscription deletion — cancels the Stripe subscription on the connected account using per-request `RequestOptions` (thread-safe) |
| `MonetizationUtil` | Shared helpers (proxy configuration, tier lookup) |
| Model classes | `MonetizationSharedCustomer`, `MonetizationPlatformCustomer`, `MonetizedSubscription`, `GraphQLClient` / `graphQLResponseClient` (Choreo analytics), `QueyAPIAccessTokenInterceptor` |

**Metered billing** works by periodically calling the Choreo Analytics GraphQL API to retrieve per-subscription request counts, then reporting usage to Stripe via `UsageRecord.create()` against the metered subscription item. Elasticsearch is also supported as an alternative analytics backend.

---

### stripe-webhook

**Artifact:** `org.wso2.apim.monetization.webhook-<version>.war`  
**Deploy to:** `<APIM_HOME>/repository/deployment/server/webapps/` renamed to `api#am#stripe.war`  
**URL base:** `https://<host>:9443/api/am/stripe/`  
**Framework:** Apache CXF JAX-RS (bundled inside the WAR to avoid the Gson classloader conflict with the Carbon OSGi runtime)

> The webhook WAR intentionally does **not** include the stripe-java SDK. Stripe event payloads are parsed with Jackson and signatures are verified natively via HMAC-SHA256. The stripe-plugin JAR is accessed as a provided OSGi dependency at runtime.

| Endpoint | Method | Purpose |
|---|---|---|
| `/webhook` | POST | Receives Stripe push events. Verifies the `Stripe-Signature` header, resolves the tenant from the `?tenantDomain=` query parameter, and dispatches to the appropriate handler. |
| `/checkout-url` | GET | Returns the pending Stripe Checkout URL for a given workflow reference. Used by the DevPortal UI to redirect the subscriber to Stripe's hosted payment page. |
| `/complete-session` | GET | Browser-redirect fallback path. After checkout, Stripe redirects the browser to `success_url?session_id={id}`. This endpoint approves the APIM workflow if the primary webhook delivery was delayed. |
| `/portal-url` | GET | Creates a Stripe Billing Portal session for a given APIM subscription and returns the hosted URL. The subscriber uses this to update payment methods, view invoices, and manage their subscription. |

**Stripe events handled:**

| Event | Action |
|---|---|
| `checkout.session.completed` | Approves the pending APIM subscription workflow; activates the subscription |
| `checkout.session.expired` | Marks the session `EXPIRED` in the DB; rejects the pending workflow |
| `invoice.payment_succeeded` | Unblocks the APIM subscription if it was previously blocked |
| `invoice.payment_failed` | Blocks the APIM subscription |
| `invoice.payment_action_required` | Blocks the APIM subscription (requires subscriber SCA/3DS re-authentication) |
| `customer.subscription.updated` | Blocks the APIM subscription when the Stripe status transitions to `past_due` or `unpaid` |
| `customer.subscription.deleted` | Blocks the APIM subscription when the Stripe subscription is cancelled externally |

---

## Subscription Creation Workflow

```
Subscriber (DevPortal)
  │
  │  1. Subscribes to a monetized API tier
  ▼
APIM (StripeSubscriptionCreationWorkflowExecutor.execute)
  │
  │  2. Loads the API's ConnectedAccountKey and the tier's Stripe Price ID
  │  3. Creates a Stripe Checkout Session (SUBSCRIPTION mode) on the connected account
  │  4. Saves the session to AM_STRIPE_CHECKOUT_SESSIONS (status: PENDING)
  │  5. Returns HTTP redirect URL to the DevPortal
  ▼
Stripe Checkout (hosted payment page)
  │
  │  6. Subscriber enters payment details and confirms
  ▼
Two parallel paths:
  │
  ├── 7a. Stripe fires checkout.session.completed webhook
  │         → WebhookApiServiceImpl verifies HMAC signature
  │         → Calls WorkflowExecutorFactory.complete(APPROVED)
  │         → APIM activates subscription (status: UNBLOCKED)
  │
  └── 7b. Stripe redirects browser to success_url?session_id=...
            → CompleteSessionApiServiceImpl (GET /complete-session)
            → Idempotent: skips if already COMPLETED
            → Calls WorkflowExecutorFactory.complete(APPROVED)
```

---

## Subscription Deletion Workflow

```
Publisher or Subscriber initiates unsubscription in APIM
  │
  ▼
StripeSubscriptionDeletionWorkflowExecutor.complete
  │  Looks up Stripe subscription ID from AM_MONETIZATION_SUBSCRIPTIONS
  │  Cancels the Stripe subscription via Subscription.cancel(requestOptions)
  │  requestOptions uses per-request API key + setStripeAccount(connectedAccountKey)
  │  Deletes row from AM_MONETIZATION_SUBSCRIPTIONS
  ▼
APIM removes the subscription record
```

---

## Billing Events Lifecycle

```
Stripe (recurring billing)
  │
  │  Fires webhook to https://<host>:9443/api/am/stripe/webhook?tenantDomain=<tenant>
  ▼
WebhookApiServiceImpl
  │
  │  Resolves tenant → loads per-tenant webhook secret from tenant-conf.json
  │  Verifies Stripe-Signature (HMAC-SHA256, 5-minute replay tolerance)
  │
  ├── invoice.payment_succeeded  →  unblock APIM subscription
  ├── invoice.payment_failed     →  block  APIM subscription
  ├── invoice.payment_action_required  →  block (SCA required)
  └── customer.subscription.updated (past_due/unpaid)  →  block

  Block/unblock path:
    ApiMgtDAO.updateSubscriptionStatus(id, "BLOCKED"|"UNBLOCKED")
    Derives tenant from API provider name via MultitenantUtils.getTenantDomain(providerName)
    Fires SubscriptionEvent via APIUtil.sendNotification → gateway cache invalidated
```

---

## Multi-tenant Isolation

Each WSO2 tenant registers a **separate webhook URL** in their Stripe Dashboard, including their tenant domain as a query parameter:

```
https://apim.example.com:9443/api/am/stripe/webhook?tenantDomain=tenant-a.com
https://apim.example.com:9443/api/am/stripe/webhook?tenantDomain=tenant-b.com
```

The webhook secret and platform account key for each tenant are read from that tenant's `tenant-conf.json` via `APIUtil.getTenantConfig(tenantDomain)`. An event delivered with a forged `tenantDomain` value will fail HMAC verification because the attacker cannot possess that tenant's Stripe signing secret.

Tenant resolution for block/unblock notifications does not rely on `PrivilegedCarbonContext` (which is always `carbon.super` in the webhook WAR). Instead, the tenant is derived from the API provider name stored in the subscription record.

---

## Setup Guide

### Prerequisites

- WSO2 API Manager 4.5.0
- A Stripe account (platform account)
- JDK 11+, Maven 3.6+

---

### Step 1 — Build from Source

```bash
git clone https://github.com/wso2-extensions/wso2-am-stripe-plugin.git
cd wso2-am-stripe-plugin

# Build the plugin first, then the webhook (webhook depends on the plugin JAR)
mvn clean install -pl stripe-plugin
mvn clean install -pl stripe-webhook
```

Build artifacts:

| File | Location |
|---|---|
| `org.wso2.apim.monetization.impl-1.5.1-SNAPSHOT.jar` | `stripe-plugin/target/` |
| `org.wso2.apim.monetization.webhook-1.5.1-SNAPSHOT.war` | `stripe-webhook/target/` |

---

### Step 2 — Stripe Side Setup

#### 2.1 Platform account

1. Log in to the [Stripe Dashboard](https://dashboard.stripe.com) with the account that will act as the platform (typically the WSO2 tenant admin's account).
2. Navigate to **Settings > Connect settings** and enable Stripe Connect if it is not already enabled.
3. Note the account's **Secret key** (`sk_live_...` or `sk_test_...`). This is the `BillingEnginePlatformAccountKey`.

#### 2.2 Connected account (per API provider)

Each API provider needs a connected Stripe account:

1. In the Stripe Dashboard, go to **Connect > Accounts** and create or link the provider's account.
2. Note the connected account ID (`acct_...`). This is the `ConnectedAccountKey` set on each API in APIM.

#### 2.3 Billing Portal configuration (per connected account)

The Billing Portal must be configured once on each connected account before subscribers can use it:

1. Log in to the Stripe Dashboard **as the connected account** (or use the platform account and select the connected account via the account switcher).
2. Go to **Settings > Billing > Customer portal** and configure the portal (allowed actions, branding, return URL defaults).
3. Save the configuration. No additional key is required — the portal is accessed via the platform key with `setStripeAccount`.

#### 2.4 Webhook endpoint (per tenant)

Register a webhook endpoint in the Stripe Dashboard for each connected account:

1. In the connected account's Stripe Dashboard, go to **Developers > Webhooks > Add endpoint**.
2. Set the endpoint URL to:
   ```
   https://<apim-host>:9443/api/am/stripe/webhook?tenantDomain=<tenant-domain>
   ```
   For `carbon.super` (single-tenant or default tenant), use:
   ```
   https://<apim-host>:9443/api/am/stripe/webhook
   ```
3. Select the following events to listen for:
   - `checkout.session.completed`
   - `checkout.session.expired`
   - `invoice.payment_succeeded`
   - `invoice.payment_failed`
   - `invoice.payment_action_required`
   - `customer.subscription.updated`
   - `customer.subscription.deleted`
4. Save the endpoint and copy the **Signing secret** (`whsec_...`). This becomes `StripeWebhookSecret` in the APIM tenant configuration.

---

### Step 3 — APIM Side Setup

#### 3.1 Deploy the artifacts

```bash
# Copy the plugin OSGi bundle
cp stripe-plugin/target/org.wso2.apim.monetization.impl-1.5.1-SNAPSHOT.jar \
   <APIM_HOME>/repository/components/dropins/

# Deploy the webhook WAR (the filename controls the context path)
cp stripe-webhook/target/org.wso2.apim.monetization.webhook-1.5.1-SNAPSHOT.war \
   <APIM_HOME>/repository/deployment/server/webapps/api#am#stripe.war
```

#### 3.2 Apply database schema

Run the following DDL statements against the APIM database:

```sql
CREATE TABLE AM_STRIPE_CHECKOUT_SESSIONS (
    ID              INT             NOT NULL AUTO_INCREMENT,
    SESSION_ID      VARCHAR(255)    NOT NULL,
    WORKFLOW_REFERENCE VARCHAR(255) NOT NULL,
    SUBSCRIBER_ID   INT             NOT NULL,
    TENANT_ID       INT             NOT NULL,
    API_UUID        VARCHAR(255),
    CHECKOUT_URL    VARCHAR(2048),
    STATUS          VARCHAR(20)     NOT NULL DEFAULT 'PENDING',
    CREATED_AT      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UPDATED_AT      TIMESTAMP,
    PRIMARY KEY (ID),
    UNIQUE (SESSION_ID)
);
```

> The tables `AM_MONETIZATION`, `AM_MONETIZATION_SUBSCRIPTIONS`, `AM_MONETIZATION_SHARED_CUSTOMERS`, `AM_MONETIZATION_PLATFORM_CUSTOMERS`, and `AM_POLICY_PLAN_MAPPING` are created by the standard APIM schema scripts.

#### 3.3 Configure the tenant

Edit `<APIM_HOME>/repository/conf/deployment.toml` or the tenant's `tenant-conf.json` to add the Stripe configuration under `MonetizationInfo`:

```json
"MonetizationInfo": {
    "BillingEnginePlatformAccountKey": "sk_live_...",
    "StripeWebhookSecret": "whsec_..."
}
```

For the Carbon super tenant, this block goes inside the `monetizationInfo` section of `/repository/conf/deployment.toml`:

```toml
[apim.monetization]
monetization_impl = "org.wso2.apim.monetization.impl.StripeMonetizationImpl"

[apim.monetization.billing_engine]
platform_account_key = "sk_live_..."
webhook_secret = "whsec_..."
```

> Refer to the [WSO2 APIM documentation on API monetization](https://apim.docs.wso2.com/en/latest/design/api-monetization/monetizing-an-api/) for the complete tenant configuration structure.

#### 3.4 Configure the workflow executors

In `<APIM_HOME>/repository/conf/deployment.toml`, replace the default subscription workflow executors:

```toml
[apim.workflow]
enable = true
service_url = "https://localhost:9445/bpmn"

[[apim.workflow.extensions]]
type = "SubscriptionCreation"
executor = "org.wso2.apim.monetization.impl.workflow.StripeSubscriptionCreationWorkflowExecutor"

[[apim.workflow.extensions]]
type = "SubscriptionDeletion"
executor = "org.wso2.apim.monetization.impl.workflow.StripeSubscriptionDeletionWorkflowExecutor"
```

Configure the Checkout success and cancel redirect URLs on the creation executor:

```xml
<!-- In <APIM_HOME>/repository/resources/workflow-extensions.xml -->
<WorkflowExtensions>
  <SubscriptionCreation
      executor="org.wso2.apim.monetization.impl.workflow.StripeSubscriptionCreationWorkflowExecutor">
    <Property name="checkoutSuccessUrl">
      https://devportal.example.com/apis/complete-checkout?session_id={CHECKOUT_SESSION_ID}
    </Property>
    <Property name="checkoutCancelUrl">
      https://devportal.example.com/apis/subscription-cancelled
    </Property>
  </SubscriptionCreation>
  <SubscriptionDeletion
      executor="org.wso2.apim.monetization.impl.workflow.StripeSubscriptionDeletionWorkflowExecutor"/>
</WorkflowExtensions>
```

The `{CHECKOUT_SESSION_ID}` placeholder is a Stripe template variable substituted at redirect time. The success URL should route to the `GET /api/am/stripe/complete-session?session_id={CHECKOUT_SESSION_ID}` endpoint (either directly or via a DevPortal page that calls it).

#### 3.5 Configure an API for monetization

In the APIM Publisher portal:

1. Open the API and go to **Monetization**.
2. Enable monetization and enter the `ConnectedAccountKey` (`acct_...`) for the API provider's Stripe connected account.
3. For each subscription tier, set the billing plan type (**Fixed Rate** or **Dynamic Rate**) and the corresponding price details.
4. Save. APIM will call `StripeMonetizationImpl.createBillingPlan()`, which creates a Stripe Product and Price on the connected account and stores the IDs in `AM_POLICY_PLAN_MAPPING` and `AM_MONETIZATION`.

#### 3.6 Metered (usage-based) billing

For metered billing tiers, configure the usage reporting job to run periodically:

```toml
[apim.monetization.usage_publisher]
analytics_access_token = "<Choreo or on-prem analytics token>"
choreo_insight_api_endpoint = "https://analytics.choreo.dev/graphql"
```

APIM calls `StripeMonetizationImpl.publishMonetizationUsageRecords()` on a schedule. This queries the analytics backend for per-subscription request counts and calls `UsageRecord.create()` on Stripe for each metered subscription item.

---

## Building Only One Module

If you make changes only to the webhook, you can rebuild it alone after the plugin is already installed in your local Maven repository:

```bash
# Plugin must be installed first
cd stripe-plugin && mvn clean install

# Then rebuild webhook
cd ../stripe-webhook && mvn clean install
```

---

## Contributing

Check the [issue tracker](https://github.com/wso2-extensions/wso2-am-stripe-plugin/issues) for open issues. Pull requests are welcome.
