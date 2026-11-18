# GoldSky Aegis Flex — Controller API v1.1 (draft)

Status: **draft for review with Outdoor Vend, 2026-11-18.** v1 (`flex_controller_api.md`) is live on the
terminal. v1.1 is built on the terminal side (Aegis Flex, `FlexController.Http`, unit-tested against a scripted
controller; `tools/flex-controller-sim` plays v1.1 by default) but not yet tried on a Q3mini. Nothing here is
final until both sides agree. Section 5 (webhook) is not built yet.

What v1.1 adds to v1:

- **Pickup in stages.** The dispense request is accepted at once and the terminal follows the order's progress,
  so it can start the on-screen countdown and the audio prompt **when your shutter is fully open**, clear it
  when pickup finishes early, and say when the pickup window has ended.
- **What was actually taken.** Your final result reports `taken_quantity`; on a pre-authorized sale the
  terminal charges the matching package price and releases the rest.
- **Stock per slot** in the status, shown in GoldSky Cael.
- **Records to your cloud** (webhook) — sample messages at the end.

A v1 controller keeps working unchanged (see "Compatibility").

---

## Connection (unchanged from v1)

- The terminal (WizarPOS Q3mini UPT) reaches your controller over the site LAN (Ethernet), by HTTP.
  **Only the terminal opens connections**; your controller never needs to reach the terminal.
- Your controller runs a small HTTP server, e.g. `192.168.1.50:8080`; the address is set on the terminal
  (technician panel) or remotely from GoldSky Cael.
- Optional shared token: every request carries `Authorization: Bearer <token>`; reject others with `401`.
- JSON, UTF-8. Ignore unknown fields — new optional fields may be added within v1.x.
- No card data is ever sent to the controller.
- Times are epoch milliseconds. Durations (`*_remaining_ms`) are used wherever the terminal draws a countdown,
  so the two devices' clocks never have to agree.

---

## 1. Status — `GET /flex/v1/status`

Every 10 seconds, 3-second timeout. Not ready or no answer → the terminal stops selling ("NOT AVAILABLE").

```json
{
  "api_version": "1.1",
  "ready": true,
  "message": "OK",
  "sold_out": [],
  "stock": { "1": 42, "2": 42, "3": 42 }
}
```

| Field | Type | Meaning |
|---|---|---|
| `api_version` | string | `"1.1"`. Absent = v1 controller. |
| `ready` | boolean | Able to hand over products now. |
| `message` | string, optional | Shown to the technician, e.g. `"shutter fault"`. |
| `sold_out` | string[], optional | Slots that can't be sold now (grey "SOLD OUT" tile). |
| `stock` | object, optional | Units left per slot, by your count / scale. Shown in Cael (inventory). Informational: selling is governed by `ready` / `sold_out`. |

For firewood all three packages draw on the same bags, so `stock` may simply repeat the bag count for every slot.

---

## 2. Start a hand-over — `POST /flex/v1/dispense`

Sent once, right after the payment is approved.

```json
{
  "api_version": "1.1",
  "order_id": "FLX-1795010000000",
  "product_id": "b0c6e0d2-6c1f-4d0e-9a51-3f1f6a0c9b21",
  "slot": "3",
  "name": "3 Bags",
  "quantity": 3,
  "amount_cents": 2100,
  "currency": "CAD",
  "payment_method": "CARD",
  "payment_flow": "PREAUTH",
  "charge_by_quantity": { "0": 0, "1": 800, "2": 1500, "3": 2100 },
  "terminal_sn": "WP6350VQ36000255",
  "deadline_ms": 1795010030000,
  "complete_by_ms": 1795010300000
}
```

| Field | Meaning |
|---|---|
| `order_id` | Unique per sale; the transaction ID used everywhere (terminal, Cael, webhook). Idempotency key. |
| `product_id`, `slot`, `name` | The package as configured in Cael. |
| `quantity` | Units paid for. |
| `amount_cents`, `currency` | Approved amount. With `PREAUTH` this is the amount **held** on the card, not yet charged. |
| `payment_method` | `CARD` or `VIP` (GoldSky member QR code). |
| `payment_flow` | `SALE` = already charged. `PREAUTH` = held; the final charge follows your result. |
| `charge_by_quantity` | What the customer will be charged for each possible `taken_quantity` (from the package prices in Cael). For your records — you don't calculate money. |
| `terminal_sn` | The terminal's serial number. |
| `deadline_ms` | **Do not open the shutter after this time** (now + 30 s). |
| `complete_by_ms` | Your final result must be readable by this time (now + 5 min). |

Answer **at once** (within 3 s) with the order — same shape as section 3:

`202 Accepted`
```json
{ "order_id": "FLX-1795010000000", "state": "ACCEPTED" }
```

or, if you can't serve it:

`200 OK`
```json
{ "order_id": "FLX-1795010000000", "state": "FAILED", "reason": "shutter fault" }
```

**Idempotency:** the same `order_id` again → return the order's current state; never start a second hand-over.

---

## 3. Follow the order — `GET /flex/v1/orders/{order_id}`

The terminal asks every 500 ms (2-second timeout per request) from acceptance until a final state.

```json
{
  "order_id": "FLX-1795010000000",
  "state": "OPEN",
  "quantity": 3,
  "pickup_remaining_ms": 57400
}
```

### States

| `state` | Final? | Extra fields | What the terminal does |
|---|---|---|---|
| `ACCEPTED` | no | — | "Please wait…" |
| `OPENING` | no | — | "Opening…" (you have recorded the starting weight and asked the shutter to open) |
| `OPEN` | no | `pickup_remaining_ms` (required) | On the first `OPEN`: audio **"Please take your 3 bags."** Screen: **"Take your 3 bags — 57 seconds remaining"**, counting down; every answer re-syncs the count. |
| `CLOSING` | no | `pickup_end`: `"TAKEN"` or `"TIMEOUT"` | Countdown cleared. `TAKEN` (finished early): "Thank you — finishing up…". `TIMEOUT`: audio + screen **"The pickup window has ended."** |
| `COMPLETED` | **yes** | `taken_quantity` (required), optional `start_weight_g`, `end_weight_g` | Settles the payment (section 4) and shows what was charged. |
| `FAILED` | **yes** | `reason` | Nothing was handed over: the payment is voided / released. "Sorry, nothing was charged." |

Rules:
- States only move forward: `ACCEPTED → OPENING → OPEN → CLOSING → COMPLETED`; `FAILED` from any non-final state
  where **nothing was taken**. If anything may have been taken, answer `COMPLETED` with your best `taken_quantity`.
- The pickup window length and the shutter closing are yours (your local protective controls); the terminal
  only displays what you report.
- `404` for an order you never accepted.
- Keep finished orders for at least 7 days and answer them the same way (recovery after a terminal restart).

### Final result example

```json
{
  "order_id": "FLX-1795010000000",
  "state": "COMPLETED",
  "quantity": 3,
  "taken_quantity": 2,
  "start_weight_g": 512400,
  "end_weight_g": 496300
}
```

---

## 4. Money rules

**Only a `COMPLETED` for the same `order_id` keeps money.** `FAILED`, an HTTP error, an unknown order, or no
final state by `complete_by_ms` → the payment is voided / released and the customer is not charged. If your
controller handed something over but its result never reached the terminal, the customer is not charged; both
sides log every order so it can be reconciled by `order_id`.

On `COMPLETED`, the charge is `charge_by_quantity[taken_quantity]`:

| Case | `PREAUTH` (recommended) | `SALE` |
|---|---|---|
| `taken_quantity` = `quantity` | Hold completed in full | Charge kept |
| fewer taken (e.g. paid 3, took 2) | Completed at the 2-bag price; rest of the hold released | Full charge kept; recorded as **SHORT** for the operator to refund from the payment back office |
| 0 taken | Hold released | Payment reversed |
| more taken than paid | Completed at the held amount (never more); recorded as **OVER** | Charge kept; recorded as **OVER** |

Notes on card types (Nuvei through the WizarPOS unattended payment app):
- The flow (`SALE` / `PREAUTH`) is chosen per terminal before the card is tapped; it can't differ by card type.
- Credit: the unused part of a hold is normally released by the card issuer shortly after completion.
- Interac debit: a pre-authorization must be completed within 2 hours (v1.1 finishes within 5 minutes); after a
  partial completion the unused part can stay held on the customer's account for 7–10 days.
- Once completed, a charge can't be adjusted from the terminal; corrections go through the payment back office.

---

## 5. Records to your cloud — webhook (part of the integration project)

GoldSky Cael sends one `POST` to your HTTPS endpoint when an order reaches its final state (and again if it is
later voided from Cael). Headers: `X-GoldSky-Timestamp` (epoch ms) and
`X-GoldSky-Signature: sha256=<hex HMAC-SHA256 of "<timestamp>.<body>" with the shared secret>`. Retried with
back-off until you answer `2xx`; deduplicate by `event_id`.

```json
{
  "event_id": "evt_01JD3Q2N8T4Y7",
  "event": "order.completed",
  "order_id": "FLX-1795010000000",
  "terminal_sn": "WP6350VQ36000255",
  "store": "Pilot site 1",
  "product": { "id": "b0c6e0d2-…", "slot": "3", "name": "3 Bags" },
  "quantity": 3,
  "taken_quantity": 2,
  "adjustment": "SHORT",
  "payment": {
    "method": "CARD",
    "flow": "PREAUTH",
    "card_brand": "INTERAC",
    "held_cents": 2100,
    "charged_cents": 1500,
    "released_cents": 600,
    "currency": "CAD",
    "reference": "000074"
  },
  "paid_at": "2026-11-18T18:13:20Z",
  "completed_at": "2026-11-18T18:15:02Z"
}
```

| `event` | When |
|---|---|
| `order.completed` | Product handed over; money settled as in section 4. `adjustment`: `NONE`, `SHORT` or `OVER`. |
| `order.failed` | Nothing handed over; payment voided / released (`charged_cents` 0, `reason`). |
| `order.voided` | Voided later from Cael by the operator. |

No card numbers or cardholder data, only the brand and the processor's reference.

---

## 6. A whole sale — paid for 3 bags, took 2

| Time | Terminal → controller | Controller answers | Customer sees / hears |
|---|---|---|---|
| 0 s | — | — | Taps "3 Bags $21.00", taps card; $21.00 held |
| 1 s | `POST /dispense` (quantity 3) | `202 ACCEPTED` | "Please wait…" |
| 1.5 s | `GET /orders/FLX-…` | `OPENING` | "Opening…" |
| 6 s | `GET /orders/FLX-…` | `OPEN`, `pickup_remaining_ms` 60000 | 🔊 "Please take your 3 bags." · "Take your 3 bags — 60 seconds remaining" |
| 6–40 s | every 0.5 s | `OPEN`, counting down | countdown |
| 40 s | | `CLOSING`, `pickup_end` `TAKEN` | countdown cleared, "Finishing up…" |
| 52 s | | `COMPLETED`, `taken_quantity` 2 | "You took 2 bags — charged $15.00. $6.00 released." |
| 53 s | Cael → your cloud | webhook `order.completed`, `SHORT` | — |

If the customer had not finished within 60 s: `CLOSING` with `pickup_end` `TIMEOUT` → 🔊 "The pickup window has
ended." and then `COMPLETED` with whatever was taken (or `FAILED` if nothing was).

---

## Compatibility

- A controller whose `POST /flex/v1/dispense` answers `200` with `{"result": "DISPENSED" | "FAILED"}` is treated
  as v1 (single blocking answer, no countdown).
- A v1.1 controller answers with `state`; the terminal then follows section 3.

## Testing without the machine

`tools/flex-controller-sim` and the Cael test simulator will play v1.1 (all states, short pickups, timeouts)
before the pilot, so your EC3320 software and our terminal can be tested apart and then together.
