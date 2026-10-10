# Handoff: Iris (WizarPOS Q2) — next steps (2026-10-10)

Read `CLAUDE.md` first (module layout, build commands, conventions). This file only covers
the work in progress and what is left. Reply/UI language with the owner: Chinese; code,
comments and commit messages: English, matching the surrounding style.

## Context

- **Iris** (`app/iris`, `feature/retail`) runs on a **WizarPOS Q2** (SN `WP6360PQ26000005`,
  Android 14, 720x1280, camera-only scanner, built-in printer) at **JiaJia Supuer Maket 佳佳超市**
  (org `21279602-373c-4849-be0e-93aaa0b4b90b`). Its role: **line-busting + delivery collection**,
  not the main checkout lane (that is Ourea, `app/ourea`).
- Payments go through **PAYWizard over a local socket (P3 protocol, port 6031)**, Nuvei acquirer.
  Test card / test environment.
- Backend: Supabase (same DB as CMP). Schema changes are SQL files in `docs/migrations/`,
  applied to prod by hand with `psql` (ask the owner for the connection — never commit secrets).
- CMP web admin is the sibling repo `C:\workspace\gs-ssp-cmp` (React + Vite + shadcn,
  i18n in `src/i18n/translations.ts`, zh + en).
- Nothing has been pushed: both repos have local commits only. Don't push or deploy without asking.

## What exists now (all committed locally)

| Area | Where |
|---|---|
| Per-product tax (`attributes.tax_rate_bps`, 0 / 1300 HST), rounded once per rate | `core/data/.../SalesTax.kt` |
| Sale lines → `transaction_items` table + itemized receipt (H = taxable) | `TransactionRepository.recordItems`, `ReceiptPrinterManager` |
| Tip step = per-device setting (off unless RESTAURANT mode) | `DeviceRepository.isTipsEnabled`, Settings |
| Delivery orders: CMP page → Iris DELIVERY tab, collect by card or cash | `delivery_orders` tables, `DeliveryOrderRepository`, `DeliveryScreen.kt`, CMP `DeliveryOrders.tsx` |
| Continuous camera scanning in a top-of-screen overlay | `WizarPosScannerProvider` (overlay mode, dp units, held stop), `MainActivity.startScanSession` |
| Staff sign-in (4-digit PIN, PBKDF2-SHA256), roles CASHIER / DRIVER / MANAGER, idle lock 5 min | `staff` table, `StaffRepository`, `PinHasher`, `StaffPinLockScreen`, CMP `StaffManagement.tsx` |
| Whole-sale refund with manager PIN approval | `TransactionHistoryScreen.kt`, `ManagerApprovalDialog.kt`, `MainActivity.refundSale` |
| Retail catalog editor + CSV/Excel import; canonical barcodes (12-digit UPC → EAN-13) | CMP `RetailProducts.tsx`, `retailCatalogService.ts`; `Barcodes.kt` |
| Anonymous-auth fix (await init + mutex) | `SupabaseClientProvider.ensureAuthenticated` |
| **Day report core** (WIP) | `core/data/.../DayReport.kt` + `DayReportTest.kt` |

## Theme + Day report (reviewed and committed: `5e253e5`, `8fc9d59`)

The dark (CYBER) / light (CLASSIC) theme and the Day report screen written by another tool were
reviewed and fixed before committing: day filtering now uses local-day windows on parsed UTC
timestamps (it used a UTC date-prefix match), refunds are listed with time/method/approver, staff
rows show cash/card/refunds, the canned "APEX AI INSIGHTS" text and the always-OK hardware icons
were removed (header shows the real battery level), theme switches repaint immediately. Both themes
stay; the user picks in Settings (default dark).

## Theming (commits `c313ef4`, `a145764`)

All Iris colours come from `core/ui/.../theme`: `GoldSkyTheme(dark)` (Material colour schemes for
dark "CYBER" navy+cyan and light "CLASSIC" paper+blue) and `GsTheme.colors` (success / warning /
danger / emphasis per theme). Rule: **no hex colours and no `isCyber` checks in screens** — use
`MaterialTheme.colorScheme.*` roles (background = page, surface = bars, surfaceVariant + outline =
cards, secondaryContainer = raised controls, primary = accent) and `GsTheme.colors`. Hex values
live only in `theme/Color.kt`. Not yet checked screen-by-screen on the Q2 in both themes (needs a
signed-in manager).

## TODO, in order

### 1. Day report screen on Iris — DONE in code; still to verify on the Q2 as a manager (print included)
Core already does the maths and fetch:
- `DayReport.build(rows, items, inDay, basis)` — sales by method and by staff, HST / taxable /
  exempt / tips, refunds (time, amount, method, status, approver), net, expected cash.
- `DayReport.Basis`: `REFUND_DATE` (default — refunds made that day; for counting cash) and
  `SALE_DATE` (refunds of that day's sales; the day's business). **The owner wants to be able to
  choose the basis on the screen.**
- `DayReportRepository.fetch(deviceSn, startIso, endIso)` (cloud only; a terminal can only read its
  own transactions under RLS), `dayBounds(millis)`, `isoUtc(millis)`, `parseIsoMillis(ts)`
  (API-25 safe; use it for `inDay`).
- `ReceiptPrinterManager.printLines(context, lines, vendor)` for printing.

To build:
- A `DayReportScreen` composable in `feature/retail/.../screens/` (Compose + Material3 like the
  other Iris screens): date with previous/next-day arrows (default today), basis selector, sections
  for payment methods, staff, tax & tips, refunds, totals (net, expected cash); PRINT button;
  "needs a connection" message when fetch returns null.
- Entry point: a "DAY REPORT" button at the top of the Records screen, **managers only**
  (`signedIn.isManager` in `app/iris/.../MainActivity.kt`; route via the existing NavHost).
- Printed layout: 32 columns (see `ReceiptPrinterManager.row`); header with store name, device,
  date, basis, printed-by staff name.
- Verify on the Q2, commit.

### 2. Card collection of a delivery order — real-device check
Cash collection is verified; card collection (`MainActivity` → `checkout(saleOf(order))`, then
`DeliveryOrderRepository.markPaid(..., "CARD")` on success) has not been run on the device.
Create a small order in CMP, collect it by card on the Q2, check `delivery_orders.status = PAID`,
`payment_method = CARD`, and the linked transaction + `transaction_items`.

### 3. Settled-sale refund — real-device check
After the nightly 03:30 batch close, refund a previous-day card sale: Reversal fails →
"Refund needs the card" dialog → test both CUSTOMER TAPS CARD and ALREADY REFUNDED IN PORTAL.
(WizarPOS: in socket mode a Refund needs the card; card-free refunds are done in their merchant
portal, or via their cloud mode — not adopted.)

### 4. Ourea (`app/ourea`) — DONE in code (`aca1285`), not run on a device
Sale lines, staff on sales, roles and valid statuses now match Iris. Still open: Ourea has no
refund UI and no day report; whether Ourea should also move onto `GoldSkyTheme` (it keeps its own
`OureaTheme` / sidebar design) is the owner's call.

### 5. Push / deploy — only when the owner says so
Both repos have unpushed commits. CMP changes (delivery orders, staff, retail products, auth
fixes, CASH label) only reach production once CMP is deployed (Cloudflare, `wrangler.toml`).

### Pending owner decisions
- Clean up the ~46 orphaned anonymous `device_auth_map` rows for the Q2 (left by the old auth bug)?
- CMP store-wide day report (all terminals, by staff) — later, if wanted.
- Partial (per-line) refunds — deliberately not built; would need refund rows + CMP report changes.

## Gotchas learned on the Q2 (don't rediscover them)
- Kotlin incremental compile can keep an old `BuildConfig` constant inlined — after changing a
  `buildConfigField`, delete `app/iris/build/tmp/kotlin-classes` and rebuild.
- `payment_status` allows only PENDING / PAID / DECLINED / CANCELLED / VOIDED / REFUNDED;
  `payment_method` allows CREDIT_CARD / DEBIT_CARD / VIP_CARD / QR_CODE / COUPON / CASH.
- minSdk 25: no `java.time`, no `PBKDF2WithHmacSHA256` from SecretKeyFactory.
- Package visibility: Iris's manifest needs `<queries>` for `com.cloudpos.scanserver` /
  `com.wizarpos.honeywell.scanner` (already there).
- Scanner overlay: window keys are **dp**; the default full-screen ScannerActivity ignores them; a
  stop sent before the async start lands orphans the camera window (handled by holding the stop).
- PIN entry on the device can't be typed with `adb input text` — tap the keypad.
- Unit tests: `./gradlew :core:data:testDebugUnitTest`. `app/iris` unit tests don't compile on
  master either (pre-existing `EmvResultClassifierTest` internal-visibility issue) — not ours.
- CMP: `npx tsc --noEmit -p tsconfig.app.json` has one pre-existing error in `deviceService.ts`;
  i18n keys must not collide (there is an existing `retail.*` namespace — the products page uses `rcat.*`).
