# Implementation Plan — Stripe Checkout Integration

## Status Legend
- ✅ Done
- 🔲 Not started

---

## What Is Already Done

### StripeMonetizationDAO.java — Checkout Session Methods ✅

The DAO has been updated with all five checkout session methods:

| Method | Description |
|---|---|
| `saveCheckoutSession(sessionId, workflowReference, subscriberId, tenantId, apiUuid, checkoutUrl)` | INSERTs a new `AM_STRIPE_CHECKOUT_SESSIONS` row with `STATUS = PENDING` and `CREATED_AT = now()` |
| `getCheckoutUrlByWorkflowRef(workflowReference)` | SELECTs `CHECKOUT_URL` by `WORKFLOW_REFERENCE` |
| `getCheckoutSession(sessionId)` | SELECTs full session row by `SESSION_ID`, returns `Map<String, String>` |
| `getCheckoutSessionByWorkflowRef(workflowReference)` | SELECTs full session row by `WORKFLOW_REFERENCE`, returns `Map<String, String>` |
| `updateCheckoutSessionStatus(sessionId, status)` | UPDATEs `STATUS` and `UPDATED_AT` for a given `SESSION_ID` |

The map keys returned by `getCheckoutSession` / `getCheckoutSessionByWorkflowRef` are driven by constants that must be added to `StripeMonetizationConstants` (see Step 1 below).

---

## Remaining Work

### Step 1 — StripeMonetizationConstants.java 🔲

Add the following constants. These are all referenced by the new DAO methods above and will not compile without them.

#### 1a. DDL — AM_STRIPE_CHECKOUT_SESSIONS

Inferred from `saveCheckoutSession` parameter order and `updateCheckoutSessionStatus`:

```sql
CREATE TABLE AM_STRIPE_CHECKOUT_SESSIONS (
    SESSION_ID         VARCHAR(255)  NOT NULL,
    WORKFLOW_REFERENCE VARCHAR(255)  NOT NULL,
    SUBSCRIBER_ID      INT           NOT NULL,
    TENANT_ID          INT           NOT NULL,
    API_UUID           VARCHAR(255)  NOT NULL,
    CHECKOUT_URL       VARCHAR(2048) NOT NULL,
    STATUS             VARCHAR(20)   NOT NULL,
    CREATED_AT         BIGINT        NOT NULL,
    UPDATED_AT         BIGINT,
    PRIMARY KEY (SESSION_ID)
);
```

#### 1b. SQL Query Constants

| Constant Name | SQL |
|---|---|
| `ADD_CHECKOUT_SESSION_SQL` | `INSERT INTO AM_STRIPE_CHECKOUT_SESSIONS (SESSION_ID, WORKFLOW_REFERENCE, SUBSCRIBER_ID, TENANT_ID, API_UUID, CHECKOUT_URL, STATUS, CREATED_AT) VALUES (?,?,?,?,?,?,?,?)` |
| `GET_CHECKOUT_URL_BY_WORKFLOW_REF_SQL` | `SELECT CHECKOUT_URL FROM AM_STRIPE_CHECKOUT_SESSIONS WHERE WORKFLOW_REFERENCE = ?` |
| `GET_CHECKOUT_SESSION_BY_SESSION_ID_SQL` | `SELECT SESSION_ID, WORKFLOW_REFERENCE, SUBSCRIBER_ID, TENANT_ID, API_UUID, STATUS FROM AM_STRIPE_CHECKOUT_SESSIONS WHERE SESSION_ID = ?` |
| `GET_CHECKOUT_SESSION_BY_WORKFLOW_REF_SQL` | `SELECT SESSION_ID, WORKFLOW_REFERENCE, SUBSCRIBER_ID, TENANT_ID, API_UUID, STATUS FROM AM_STRIPE_CHECKOUT_SESSIONS WHERE WORKFLOW_REFERENCE = ?` |
| `UPDATE_CHECKOUT_SESSION_STATUS_SQL` | `UPDATE AM_STRIPE_CHECKOUT_SESSIONS SET STATUS = ?, UPDATED_AT = ? WHERE SESSION_ID = ?` |

> Note: `ADD_CHECKOUT_SESSION_SQL` has **8 `?` parameters** matching the 8 `ps.setXxx` calls in `saveCheckoutSession` in order: `sessionId, workflowReference, subscriberId, tenantId, apiUuid, checkoutUrl, status, createdAt`.

#### 1c. Column Name Constants

Used as map keys in `getCheckoutSession` / `getCheckoutSessionByWorkflowRef`:

| Constant Name | Value |
|---|---|
| `CHECKOUT_COL_SESSION_ID` | `"SESSION_ID"` |
| `CHECKOUT_COL_WORKFLOW_REF` | `"WORKFLOW_REFERENCE"` |
| `CHECKOUT_COL_SUBSCRIBER_ID` | `"SUBSCRIBER_ID"` |
| `CHECKOUT_COL_TENANT_ID` | `"TENANT_ID"` |
| `CHECKOUT_COL_API_UUID` | `"API_UUID"` |
| `CHECKOUT_COL_STATUS` | `"STATUS"` |

> The column name values must exactly match the SQL column names in the DDL and in the SELECT queries above.

#### 1d. Status Value Constants

Used by `saveCheckoutSession` and `updateCheckoutSessionStatus`:

| Constant Name | Value |
|---|---|
| `CHECKOUT_SESSION_STATUS_PENDING` | `"PENDING"` |
| `CHECKOUT_SESSION_STATUS_COMPLETED` | `"COMPLETED"` |
| `CHECKOUT_SESSION_STATUS_EXPIRED` | `"EXPIRED"` |

---

### Step 2 — StripeSubscriptionCreationWorkflowExecutor.java 🔲

Three separate changes to `monetizeSubscription()` and a new `complete()` override.

#### 2a. Decision 1 — New subscriber (no platform customer)

Replace the `createMonetizationPlatformCutomer(subscriber)` call with the Checkout redirect path:

1. Look up the Stripe Plan for the tier (need the currency).
2. Call `Session.create()` on the platform account:
   - `mode = SETUP`
   - `currency` from the plan
   - `success_url = checkoutSuccessUrl`
   - `cancel_url = checkoutCancelUrl`
   - `metadata`: `workflowReference`, `subscriberId`, `applicationId`, `tierName`, `apiUuid`, `tenantId`
3. Call `stripeMonetizationDAO.saveCheckoutSession(session.getId(), workflowReference, subscriberId, tenantId, apiUuid, session.getUrl())`.
4. Set `workflowDTO.setStatus(WorkflowStatus.CREATED)`.
5. Return `new HttpWorkflowResponse()` with `setRedirectUrl(session.getUrl())`.
6. Do **not** call `execute(workflowDTO)` at the end in this path.

The `workflowReference` to use as the key: use `workflowDTO.getExternalWorkflowReference()` (this is the string APIM stores in `AM_WORKFLOWS.WF_EXTERNAL_REFERENCE` and is what `retrieveWorkflowFromInternalReference` looks up by).

> Config required: `checkoutSuccessUrl` and `checkoutCancelUrl` as settable properties read from `workflow-extensions.xml` (already described in design doc Section 2.8).

#### 2b. Decision 2 — Platform customer exists, no shared customer

After `stripeMonetizationDAO.getPlatformCustomer(...)` returns a non-null customer, before `createSharedCustomer`:

1. Call `Customer.retrieve(platformCustomer.getCustomerId())` on the platform account.
2. Check `customer.getInvoiceSettings().getDefaultPaymentMethod()`.
3. **If non-null** (Checkout path customer): call new method `createSharedCustomerWithPaymentMethod(platformCustomer, requestOptions, subWorkFlowDTO)` instead of `createSharedCustomer`.
4. **If null** (legacy `tok_visa` customer): call existing `createSharedCustomer` (unchanged).

New method `createSharedCustomerWithPaymentMethod`:
1. Call `PaymentMethod.create()` with `customer = platformCustomer.getCustomerId()` and `payment_method = defaultPaymentMethodId` under connected account (`requestOptions`).
2. Call `Customer.create()` on connected account with:
   - `payment_method = clonedPm.getId()`
   - `invoice_settings.default_payment_method = clonedPm.getId()`
   - `email`, `description` (same as existing `createSharedCustomer`)
3. Save to DB via `stripeMonetizationDAO.addBESharedCustomer(...)`.

#### 2c. `complete()` override — completeStripeCheckoutSubscription()

Called by the webhook WAR after `checkout.session.completed` is received:

1. Read `checkoutSessionId` from `workflowDTO.getAttributes().get("checkoutSessionId")`.
2. Call `Session.retrieve(checkoutSessionId)` on the platform account.
3. Call `SetupIntent.retrieve(session.getSetupIntent())` on the platform account.
4. Get `paymentMethodId = setupIntent.getPaymentMethod()`.
5. Build platform customer: `Customer.create()` with `payment_method`, `invoice_settings.default_payment_method = paymentMethodId`, `email`, `description`.
6. Save platform customer via `stripeMonetizationDAO.addBEPlatformCustomer(...)`.
7. Clone payment method to connected account: `PaymentMethod.create(customer=platformCustomerId, payment_method=paymentMethodId, requestOptions)`.
8. Create shared customer on connected account with cloned PM and `invoice_settings.default_payment_method`.
9. Save shared customer via `stripeMonetizationDAO.addBESharedCustomer(...)`.
10. Create subscription: `Subscription.create(customer=sharedCustomerId, items=[{plan: planId}], requestOptions)`.
11. Save subscription via `stripeMonetizationDAO.addBESubscription(...)`.
12. Mark checkout session completed: `stripeMonetizationDAO.updateCheckoutSessionStatus(checkoutSessionId, CHECKOUT_SESSION_STATUS_COMPLETED)`.
13. Mark APIM subscription active: update workflow status → `APPROVED`, call `complete(workflowDTO)` to trigger APIM to set subscription `UNBLOCKED`.

The `apiUuid`, `tierName`, `applicationId`, `tenantId`, `subscriberId` needed in step 5–12 must be read from the checkout session metadata stored in Stripe (available in the `Session` retrieved in step 2) OR from the `AM_STRIPE_CHECKOUT_SESSIONS` DB row via `getCheckoutSessionByWorkflowRef`.

---

### Step 3 — Webhook WAR (wso2-am-stripe-webhook) 🔲

Separate Maven WAR project. Nothing in this repo.

#### Project Structure
```
wso2-am-stripe-webhook/
├── pom.xml
└── src/main/
    ├── java/org/wso2/apim/monetization/webhook/
    │   └── StripeWebhookServlet.java
    └── webapp/
        └── WEB-INF/
            └── web.xml
```

#### Key Decisions (from design doc issues log)
- **No stripe-java** — Gson classloader conflict with Carbon OSGi environment causes `NoSuchMethodError`. Use `javax.crypto.Mac` (HMAC-SHA256) natively.
- **Jackson for JSON** — parse event payload with `ObjectMapper` (already available in Carbon).
- **Delegate all Stripe API work to plugin** — webhook only extracts `workflowReference` from session metadata and calls `WorkflowExecutorFactory.getWorkflowExecutor(WF_TYPE_AM_SUBSCRIPTION_CREATION).complete(workflowDTO)`. The `complete()` override in the plugin (Step 2c) does the actual Stripe work.

#### Signature Verification Algorithm
```
signed_payload = timestamp + "." + raw_request_body
expected       = HMAC-SHA256(webhookSecret, signed_payload)
accept if      = expected == v1 value from Stripe-Signature header
                 AND abs(now_seconds - timestamp) <= 300   (5-minute window)
```

#### Servlet Flow
1. Read raw body and `Stripe-Signature` header.
2. Verify HMAC (reject with 400 if invalid/expired).
3. Parse JSON with Jackson: extract `type` and `data.object`.
4. If `type != "checkout.session.completed"` → return 200 (ignore).
5. Extract `metadata.workflowReference` from session object.
6. Call `ApiMgtDAO.getInstance().retrieveWorkflowFromInternalReference(workflowReference)` → `WorkflowDTO`.
7. Set `workflowDTO.getAttributes().put("checkoutSessionId", session.id)`.
8. Set `workflowDTO.setStatus(WorkflowStatus.APPROVED)`.
9. Call `WorkflowExecutorFactory.getWorkflowExecutor(WF_TYPE_AM_SUBSCRIPTION_CREATION).complete(workflowDTO)`.
10. Return HTTP 200 `{"received": true}`.

#### Config
Read webhook secret from `api-manager.xml`:
```xml
<Monetization>
    <StripeWebhookSecret>whsec_...</StripeWebhookSecret>
</Monetization>
```
Access via `APIManagerConfiguration.getFirstProperty("Monetization.StripeWebhookSecret")`.

---

## Implementation Order

| # | Task | File / Project |
|---|---|---|
| 1 | Add DDL for `AM_STRIPE_CHECKOUT_SESSIONS` to DB migration scripts | `dbscripts/` (separate) |
| 2 | Add all `CHECKOUT_*` constants + `STRIPE_WEBHOOK_SECRET` constant | `StripeMonetizationConstants.java` |
| 3 | Implement Decision 1 — Checkout redirect path in `monetizeSubscription()` | `StripeSubscriptionCreationWorkflowExecutor.java` |
| 4 | Implement `complete()` override — `completeStripeCheckoutSubscription()` with idempotency guard and partial failure rollback | `StripeSubscriptionCreationWorkflowExecutor.java` |
| 5 | Implement Decision 2 — `createSharedCustomerWithPaymentMethod()` | `StripeSubscriptionCreationWorkflowExecutor.java` |
| 6 | Create webhook WAR project | new `wso2-am-stripe-webhook/` |
| 7 | Webhook: handle `checkout.session.expired` — expire DB record, delete ON_HOLD APIM subscription | `wso2-am-stripe-webhook/` |
| 8 | Webhook: handle `invoice.payment_failed` — block APIM subscription via Admin REST API | `wso2-am-stripe-webhook/` |
| 9 | Webhook: handle `customer.subscription.deleted` — remove APIM subscription | `wso2-am-stripe-webhook/` |
| 10 | Webhook: handle `customer.updated` — propagate payment method update to all shared customers | `wso2-am-stripe-webhook/` |
| 11 | Per-tenant webhook secret — read `StripeWebhookSecret` from `tenant-conf.json`; two-pass tenant resolution in WAR | `wso2-am-stripe-webhook/` + `StripeMonetizationConstants.java` |

Steps 2–5 are in this repo. Steps 6–11 are in the new `wso2-am-stripe-webhook/` project.

---

## Resolved Questions

1. **`workflowReference` field** ✅ — Use `workflowDTO.getExternalWorkflowReference()` (maps to `AM_WORKFLOWS.WF_EXTERNAL_REFERENCE`). Store as metadata key `workflowReference` at `Session.create()` time.
2. **Session metadata vs DB lookup** ✅ — Use `AM_STRIPE_CHECKOUT_SESSIONS` DB row for `subscriberId`, `applicationId`, `tierName`, `apiUuid`, `tenantId`. Avoids Stripe metadata 500-char-per-key limit.
3. **Table name** ✅ — `AM_STRIPE_CHECKOUT_SESSIONS` is the new naming convention for the new table only. Existing tables (`AM_MONETIZATION_*`) are unchanged.
4. **Webhook signing secret scope** ✅ — Per-tenant in `tenant-conf.json` under `MonetizationInfo.StripeWebhookSecret`. Global `api-manager.xml` entry removed.
5. **Webhook signature verification method** ✅ — Native `javax.crypto.Mac` (HMAC-SHA256). stripe-java built-in (`Webhook.constructEvent()`) causes `NoSuchMethodError` due to OSGi Gson classloader conflict (see design doc Issue #4 and Section 5.8).
