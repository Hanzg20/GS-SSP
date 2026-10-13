# Ourea Scan end-to-end test

Customer QR page (`web/ourea-scan`) → `dine.scan_orders` → Ourea floor (accept / reject) →
kitchen ticket → the customer's status page. Cael's live console is read-only (2026-10-13):
QR orders are handled on the Ourea only. Everything runs in its own
store, **ZZ 扫码点餐测试 Scan E2E** (`e2e5ca00-0000-4000-8000-000000000001`), never a real one.

`$DB` below = `db_connection_string` from `local.properties`, with `PGPASSWORD=db_password`.

## Files

| File | What it does |
|---|---|
| `seed.sql` | Store, location, 9 dishes (modifiers, sold out, 0% tax, retired), tables T1–T4 + retired T9, staff PIN 1234 (manager) / 5678 (server). Safe to run again. |
| `reset.sql` | Clears the store's QR orders, checks and payments (the abuse limits count each table's last 10 min). Run before every pass. |
| `bind-emulator.sql` | `-v store=e2e` moves the Ourea emulator (`devices.sn = 'unknown'`) into the store; `-v store=back` returns it to GoldSky Test Store. |
| `teardown.sql` | Removes the store completely. Card transactions are kept. |
| `fake-printer.cjs` | Fake kitchen printer on :9100; prints each ticket as text. |
| `../../web/ourea-scan/scripts/scan-e2e.mjs` | 32 server checks through the customer's anon RPCs. |

QR links: `http://<host>:5173/?t=e2e000000000000000000000000000NN` (NN = 01–04, 09).
From the emulator the host is `10.0.2.2`.

## Run

```bash
psql "$DB" -f tools/scan-e2e/seed.sql -f tools/scan-e2e/reset.sql
psql "$DB" -v store=e2e -f tools/scan-e2e/bind-emulator.sql
cd web/ourea-scan && node scripts/scan-e2e.mjs          # expect: 全部通过
node tools/scan-e2e/fake-printer.cjs                    # separate terminal
cd web/ourea-scan && npx vite --host                    # separate terminal
```

Then on the emulator (Ourea, PIN 1234, Settings: Business type = DINE_IN, kitchen printer 10.0.2.2:9100):

1. Floor shows T1 "QR 1" and T4 "QR 3" from the script, banner + chime. T9 is not on the floor.
2. Review → T1: 2 × beef noodle (+中辣, 加蛋, 加牛肉, note 少放葱), 3 × water, **total $49.96** → Accept.
   T1 opens check $49.96; a kitchen ticket arrives at the fake printer.
3. Review → T4 → Reject → "Something is sold out".
4. Accept another T4 order: it is the table's round 2, ticket **加菜 ADD (第2轮)**. Cael's Live tab
   shows the same floor and pending orders, with no buttons.
5. Customer page on the emulator's browser, T2: sold-out dish greyed; beef noodle's Add stays
   disabled until a spice is picked, a third add-on can't be picked; cart $19.45 + tax $2.53 = **$21.98**;
   Send → "Waiting for a server to confirm" (never success before the server says so).
6. Accept T2 on the terminal → the customer page shows "Sent to the kitchen"; the ticket lists every option.

Afterwards: `psql "$DB" -v store=back -f tools/scan-e2e/bind-emulator.sql`.

## Gotchas

- Pass Chinese text to psql in a **file** (`-f`), not `-c`: on Windows the command line mangles it to `??`.
- Not covered here: printing on a real network printer, the AI guide (Ask Beaver), and taking
  payment on the opened checks.

## Last run: 2026-10-13, all passing

Script 32/32. Terminal accept, reject with reason, round 2 on the same table, three kitchen
tickets checked, customer page order → status. (Cael approval was also tested, then removed.)
