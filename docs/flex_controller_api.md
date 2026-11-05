# Aegis Flex — Controller API (v1)

GoldSky Aegis Flex is the payment terminal in front of your machine: the customer chooses and pays
on the terminal, and your controller hands the product over. This document is the interface
between the two. It is all your controller needs to implement — no GoldSky hardware or SDK.

## Connection

- The terminal (WizarPOS Q3mini UPT) reaches your controller over the site LAN (Ethernet), by HTTP.
- Your controller runs a small HTTP server, for example on `192.168.1.50:8080`. The technician
  enters that address on the terminal.
- Optional: a shared token. When set, every request carries `Authorization: Bearer <token>`;
  reject requests without it with `401`.
- JSON, UTF-8. Unknown fields must be ignored (new fields may be added in v1).
- No card data is ever sent to the controller.

## 1. Status — `GET /flex/v1/status`

Called every 10 seconds. While your controller is not ready (door jammed, out of stock, service
mode) or doesn't answer within 3 seconds, the terminal stops selling and shows "NOT AVAILABLE".

Response `200`:

```json
{ "ready": true, "message": "OK" }
```

| Field | Type | Meaning |
|---|---|---|
| `ready` | boolean | `true` = able to hand over products now |
| `message` | string, optional | shown to the technician, e.g. `"door sensor fault"` |

## 2. Dispense — `POST /flex/v1/dispense`

Sent once the payment is approved. Answer **when the hand-over is finished** (the customer has
taken the product, or it failed) — not before. The terminal waits until `deadline_ms` plus 10
seconds.

Request:

```json
{
  "order_id": "FLX-1793906821219",
  "product_id": "b0c6…",
  "slot": "2",
  "name": "2 Bags",
  "quantity": 2,
  "amount_cents": 1900,
  "currency": "CAD",
  "payment_method": "CARD",
  "payment_flow": "PREAUTH",
  "terminal_sn": "WP6350VQ36000255",
  "deadline_ms": 1793906911219
}
```

| Field | Meaning |
|---|---|
| `order_id` | Unique per sale. Use it as the idempotency key (see below). |
| `product_id`, `slot`, `name` | The product as configured in GoldSky Cael. |
| `quantity` | Units to hand over (e.g. bags). |
| `amount_cents`, `currency` | What the customer paid. |
| `payment_method` | `CARD` or `VIP` (GoldSky member code). |
| `payment_flow` | `SALE`: `amount_cents` is already charged. `PREAUTH`: it is only held on the card -- you may say how much of it to charge (below). |
| `terminal_sn` | The terminal's serial number. |
| `deadline_ms` | Epoch milliseconds. **Do not start a hand-over after this time** — answer `FAILED`. |

Response `200`:

```json
{ "order_id": "FLX-1793906821219", "result": "DISPENSED" }
```

or

```json
{ "order_id": "FLX-1793906821219", "result": "FAILED", "reason": "door did not open" }
```

| `result` | What the terminal does |
|---|---|
| `DISPENSED` | Keeps the payment. "TAKE YOUR ITEM". On a `PREAUTH` order, optional `amount_cents` = what to charge of the hold (e.g. by weight; never more than held; absent = all of it; `0` = release). |
| `FAILED` | Voids the payment (the customer is not charged). |

For example, a held order settled at what was actually taken:

```json
{ "order_id": "FLX-1793906821219", "result": "DISPENSED", "amount_cents": 1250 }
```

## Money rules (why the API is shaped like this)

- **Only an explicit `DISPENSED` for the same `order_id` keeps the money.** Any other answer, an
  HTTP error, or no answer by the deadline voids the payment.
- So if your controller hands over the product but its answer is lost, the customer is not
  charged. Your controller should log every order and its outcome; GoldSky Cael shows the
  terminal's side, and the two can be reconciled by `order_id`.
- **Idempotency:** if the same `order_id` arrives again, return the earlier outcome — never hand
  over twice.
- Never start after `deadline_ms`.

## Records

Every sale (paid, voided, failed) is recorded in GoldSky Cael with its `order_id`. Real-time
delivery of these records to your own cloud (webhook) is part of the integration project.

## Testing without the machine

`tools/flex-controller-sim/flex_controller_sim.py` is a stand-in controller (Python 3, no
packages): run it on a PC on the terminal's LAN and enter `<pc-ip>:8080` on the terminal.
