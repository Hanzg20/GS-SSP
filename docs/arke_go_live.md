# Arke: switching a store live

How a restaurant goes from "set up in Cael" to taking real online orders on GoldSky Arke.
Companion to `docs/online_ordering_design.md`. Written 2026-11-19.

Cael → Devices → Online Ordering shows the **Ready to go live?** card for the store being viewed
(`cmp_online_ready`, `docs/migrations/2026-11-19_arke_go_live.sql`):
- **Red** means fix it before real customers.
- **Amber** means it works, but look at it.
- **Green** means done.

The steps below are the same list, plus what only people can do.

## 0. Once, for GoldSky (not per store)

| # | What | Who | Status 2026-11-19 |
|---|---|---|---|
| 0.1 | Order emails work. The Edge Function secret `SMTP_PASS` must be the Zoho **app password**. The site now gets `535 Authentication Failed` from `smtp.zohocloud.ca:465`. | Owner | Open |
| 0.2 | A lawyer reads the two policy pages (`web/ourea-scan/src/ArkePolicy.jsx`). They state only what the system does, but they are not legal advice. | Owner | Open |
| 0.3 | The customer site is live. A push of gs-ssp `master` deploys `web/ourea-scan`. | Claude | Done |

## 1. Per store: the owner's paperwork

1. **Nuvei online (card-not-present) merchant account.** It is separate from the in-store terminal account, with its own rates and approval.
   - Nuvei gives three things: Merchant ID, Merchant Site ID and Secret Key.
   - A sys admin enters them in Cael → Organizations → the store → Nuvei online, with **Environment = Production**.
   - The self-check stays red on "Online card payments" while the account is missing, not ACTIVE, or still sandbox (`env = int`).
2. **Agree the auto-cancel minutes** with the restaurant.
   - The default is 15.
   - A paid order nobody accepts in that time is cancelled and refunded on its own.
   - The refund policy page quotes this number.

## 2. Per store: set up in Cael (store staff with catalog rights, or GoldSky)

| Check | Where | Red / amber when |
|---|---|---|
| Online ordering set up | Online Ordering → store link → Save | Never saved |
| Address and phone | Online Ordering | Either missing. They are the customer's contact on the order page, the emails and both policy pages. |
| Opening hours | Online Ordering | No day open (red). No hours at all means open 24 h (amber). |
| Online menu | Catalog (RETAIL products, not `online_hidden`) | No dishes (red), or some dishes with no price (amber) |
| Delivery zones | Online Ordering → Delivery | Delivery on with no zone. The customer page then hides delivery. |
| Auto-cancel minutes | Online Ordering | Always shown (green) |
| Google review link | Online Ordering | Optional (amber if empty) |
| Taking orders switch | Online Ordering | Off |

## 3. Per store: devices

- **Install the current Ourea APK** on the store's tablet (Iris works too for a handheld), linked to the store.
  - The check is amber if no RETAIL device of the store was seen in the last 15 minutes.
  - A store that takes orders only on Cael's order desk will always show amber here. That's fine if the desk stays open during opening hours.
- Auto-accept and printing are set where the orders are handled (Ourea, or the Cael order desk on that computer).

## 4. Per store: one real test order

On the live account, with the store's own staff:
1. Open the store link on a phone.
2. Place a small order, paying with a real card.
3. The store accepts it, marks it ready, then picked up (or delivered).
4. Check the customer got the confirmation email.
5. Optionally, a second order that the store rejects. Check the money comes back (a same-day void) and the cancellation email arrives.

The "Test order" check turns green after the first order is picked up or delivered. The "Order emails" check shows the last email's result.

## 5. Go live

1. All red lines are fixed and the amber lines have been read.
2. Print the poster (Online Ordering → Print poster) and share the link: Google Business profile, social media, receipts.
3. Watch the first day in Cael's order desk. If nobody accepts an order, Arke rejects and refunds it on its own after the auto-cancel minutes, and emails the customer.

## What the policy pages say, and where it comes from

The pages are `?s=<slug>&p=refund` and `?s=<slug>&p=privacy`. They are linked from the menu footer, under the pay button, and on the order page.

Each sentence is backed by the system. If the behaviour changes, change the page in the same commit.

| Statement | Backed by |
|---|---|
| Full card number never reaches us; we keep brand, last 4 digits and approval | Nuvei Simply Connect form; `arke-pay-dmn` stores `cardCompany` + last 4 |
| Browser keeps language, name, phone, email, address, cart, order links | `lib.js` `store`, `pay.jsx` email, `i18n.js` language |
| No ad or analytics trackers | `web/ourea-scan/index.html` loads none |
| Stored in Canada | Supabase project region `ca-central-1` |
| Declined, or not accepted in N minutes, is cancelled with everything back and an email | `2026-11-19_arke_accept_deadline.sql`, `arke-sweep` |
| Same-day void, else 3–10 business days | `voidOrRefund` in `_shared/arke-refund.ts` |
| No online cancel after acceptance; call the store | Only REJECTED orders can be refunded (`cmp_online_refund_org`) |
| Unpaid orders expire after 30 minutes | `online_order_status` |
| Points of a cancelled order are removed | Loyalty reversal (tested 2026-11-19, order A8) |
