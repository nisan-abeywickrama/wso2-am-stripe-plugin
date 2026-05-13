# WSO2 API Manager — Stripe Monetization Extension

A Stripe monetization integration for WSO2 API Manager 4.x. Enables API providers to charge subscribers for API access using Stripe's hosted Checkout flow and recurring subscription billing.

---

## What You Can Do

- **Stripe Checkout for subscriptions** — When a subscriber selects a monetized API tier in the DevPortal, APIM redirects them to a Stripe-hosted payment page.
- **Billing Portal for subscribers** — Subscribers can open a Stripe-hosted Billing Portal to update payment methods, view invoices, and manage or cancel their subscription.
- **Automatic subscription lifecycle** — Stripe webhook events (payment succeeded, failed, or action required) automatically block or unblock APIM subscriptions to match the current billing state.
- **Fixed-rate and metered billing** — Set a flat recurring price per tier, or report per-request usage to Stripe for usage-based pricing.
- **Multi-tenant support** — Each WSO2 tenant uses its own Stripe platform key and webhook secret, keeping billing data isolated between tenants.

---

## Compatibility

**This version (v1.6.0) supports WSO2 API Manager 4.5.0 and above.** For older APIM versions, use the corresponding earlier release.

| Extension Version | WSO2 API Manager Version |
|:-:|:-:|
| 1.0.x | 3.0.0 |
| 1.1.x | 3.1.0 – 3.2.0 |
| 1.2.x | 4.0.0 |
| 1.3.x – 1.5.x | 4.1.0 |
| **1.6.x** | **4.5.0** |

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
  stripe-service/     WAR — Stripe event receiver and DevPortal helper endpoints
```

---

## Components

### 1. stripe-plugin

This is the primary business logic module. It implements the WSO2 `Monetization` SPI and two workflow executors for subscription creation and deletion.

---

### 2. stripe-service

Exposes a REST API at `https://<host>:9443/api/am/stripe/`.

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

## Contributing

Check the [issue tracker](https://github.com/wso2-extensions/wso2-am-stripe-plugin/issues) for open issues. Pull requests are welcome.
