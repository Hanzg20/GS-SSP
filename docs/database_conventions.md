# Database conventions

How new tables are named and organized in the shared Supabase database (GS-SSP
apps and CMP). Agreed 2026-10-13. Existing tables move over gradually; every
NEW table follows this from now on.

## 1. One schema per business domain

`public` is no longer the place for everything. A table lives in the schema
of the domain it belongs to; the table name does not repeat the domain.

| Schema | What lives there | Status |
|---|---|---|
| `public` | Platform core for now (organizations, locations, devices, staff, products, transactions...) and the RPC functions the apps call | existing |
| `dine` | Dine-in: `tables`, `checks`, `check_items`, `payments` | **done 2026-10-13** |
| `sales` | Shared orders / lines / payments for every vertical (see 2) | planned, with the next vertical |
| `retail`, `wash`, `ev`, `parking`, `vending`, `timer` | Each vertical's own extension tables | as each is built or migrated |
| `member` | VIP / stored value / points / coupons | to migrate |
| `media` | Ads, playlists, playback logs | to migrate |
| `apex` | AI assistant (messages, knowledge) | to migrate |
| `ops` | Heartbeats, error / health / audit logs, remote commands | to migrate |
| `legacy` | `gp_*`, `jc_*`, `e_*` once their owners are confirmed | to decide |

Write `dine.checks`, not `dine.dine_checks` or `public.dine_checks`.

## 2. Shared orders, vertical extensions

Every vertical sells something. New verticals do not get their own copy of
orders / lines / payments:

- `sales.orders`: one sale (kind: COUNTER, DELIVERY, DINE_IN, WASH, EV...),
  status, store, device, staff, totals
- `sales.order_lines`: product, quantity, unit price, tax rate, options
- `sales.payments`: one or more per order (split, mixed tender, tip)

A vertical adds a 1:1 extension table keyed by `order_id` for what only it has
(`dine.checks`: table, guests, rounds; `ev.sessions`: charger, kWh). Reports,
day close, HST, Apex and CMP read `sales.*` only. Existing `transactions`,
`delivery_orders` and the dine checks move onto it step by step, with views
keeping the old names readable during the move.

## 3. Access

- A schema other than `public` is **not exposed through the API**. Apps reach
  it only through `public.<domain>_<verb>` functions (SECURITY DEFINER, the
  store taken from the calling device's `device_auth_map` row, never from a
  parameter). Example: `dine_floor()`, `dine_send_items(...)`.
- Table privileges in those schemas are revoked from `anon` / `authenticated`.
- Every store-owned row has `org_id` (and `location_id` where it matters).

## 4. Names and columns

- Tables: lower_snake_case, plural nouns (`checks`, `check_items`).
- Foreign keys: `<entity>_id`. Money: integer cents, `*_cents`. Tax rates:
  basis points, `*_bps`. Times: `timestamptz`, `*_at`. Dates: `*_day` / `*_on`.
- Status: `text` with a `CHECK (status IN (...))`, upper case values.
- Functions: `<domain>_<verb>[_<object>]` (`dine_open_check`). Every argument
  is sent by the app (optional ones have `DEFAULT NULL`), results are JSONB
  `{success, message, ...}`.
- `COMMENT ON` every schema and table: what it is, which app writes it.

## 5. JSONB or a column?

`attributes` / options JSONB for what is only displayed or configured
(dish options, display settings). As soon as something is filtered, summed,
constrained or reported on, it becomes a real column. To promote:
`products.attributes.tax_rate_bps`, `category`, `stock_qty`.

## 6. Migrations

One file per change in `docs/migrations/YYYY-MM-DD_<what>.sql`, idempotent
where possible, applied in a transaction. Planned: move to Supabase CLI
migrations (`supabase/migrations`, `supabase db push`) so each environment
records what it has applied.
