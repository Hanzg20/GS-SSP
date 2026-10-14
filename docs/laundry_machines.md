# Aegis Laundry — connecting coin-op machines (2026-10-14)

One Q3mini drives up to 8 machines through an RS485 Modbus RTU relay module (RS232→RS485
converter on the terminal's serial port; module at 9600 8N1, slave 1). Each relay closes
across the machine's **coin-drop input** ("Coin 1"), exactly as a coin mechanism or a
third-party card reader does. Code: `core/hardware/.../modbus/ModbusRelayBoard.kt`,
`feature/laundry/LaundryMachineConfig.kt`.

## How Canadian laundromats price (2025–26) — and what the terminal sells

| | Practice | Example |
|---|---|---|
| Washers | by **size**, plus a price per **cycle** (cold / warm / hot) and **add-ons** (heavy soil, extra rinse) | Toronto: 20 lb $6, 40 lb $10, 60 lb $14 (Soda Laundry) |
| Dryers | by **time**, topped up as needed | $1 per 12 min (Toronto), $1 per 8 min (Ottawa), 25¢ per 9 min; ~$3 dries most loads |
| Card readers | several price options per machine; dryer top-off time | LaundroWorks, Nayax |

So after a machine is picked the terminal asks: washer → **water temperature** (Cold included /
Warm +$0.50 / Hot +$1.00) and **extras** (Heavy soil +$0.50, Extra rinse +$0.25); dryer →
**drying time** (12 / 24 / 36 / 48 min = $1–$4). The total is charged (card, VIP balance or
coupon) and recorded (`amount`, and the choice in `order_note`, e.g. "Hot · Heavy soil").
All prices come from Cael (product attributes below); these are only the defaults.

## Two ways to connect, and which to use

**Coin-pulse mode (default — needed for cycle prices and dryer time).** The machine stays
in coin mode, each pulse = one coin (`coin_cents`, usually 25). The terminal sends
total ÷ coin pulses; the machine shows the credit and lets the customer run any cycle the
credit covers, so the done screen tells them what to select ("Select Hot · Heavy soil,
then press START"). Requirements:
- **the cycle prices programmed on the machine must equal the ones in Cael** (base + adders);
  a cheaper cycle chosen on the machine leaves credit on it, a dearer one asks for more;
- every price must be a whole number of coins (otherwise the product is refused).

**Single-pulse start (fixed price).** The machine starts a cycle on one pulse
(`"pulse_count": 1`, see the table below for the setting); the price lives only in Cael. Simple,
but the terminal can't charge per cycle or sell dryer time — the product then has no options.

## Popular machines

| Brand / control | Where to connect | Machine setting for single-pulse start | Notes |
|---|---|---|---|
| Speed Queen / Huebsch / UniMac (Alliance) — Netmaster (SCxxN) | Dedicated pulse interface on the control board | Start Pulse Mode = single pulse: setting 124 → 128; displayed price can be 1 | |
| Speed Queen / Huebsch — B-micro with "A" controls (SCxxAC) | Coin 1 input (coin drop removed) | Pulse value = cycle price, or cycle price = one quarter | |
| LG commercial (Giant-C, Titan-C) | Coin connector (2 wires, 16.5 V) at the meter case, "Coin 1" | Vend type = **Coin** (pulse); Coin value 25 for pulse-only | Card mode is serial — don't use it for pulse; getting back to coin mode needs the serial harness |
| Milnor | Coin 1 / Coin 2 input | One pulse = full vend price, or adjust the machine's coin value | |
| Laundrylux (Electrolux / Wascomat) | Pulse input | Pulse mode (washers can also do serial "card mode"; dryers pulse only) | |

## Timing

No published standard. Coin acceptors offer 25/45/65 ms or 20/50/100 ms pulses; 50 ms is
the common middle. Defaults: `pulse_ms` 50, `gap_ms` 100. A pulse that is too long, or one
too soon after the last, can raise a machine's coin error — adjust per machine in the
product's attributes. The real contact time is `pulse_ms` plus the module's reply to the
OFF command (tens of ms at 9600 baud): **measure it on the first installation**.

## Product attributes (Cael › products, vertical LAUNDRY)

One product per machine. Washer (coin-pulse mode, price = cheapest cycle):

```json
{ "machine_no": 4, "relay": 4, "kind": "WASHER", "size_lb": 40, "coin_cents": 25,
  "temps":  [{"name":"Cold","add_cents":0}, {"name":"Warm","add_cents":50}, {"name":"Hot","add_cents":100}],
  "addons": [{"name":"Heavy soil","add_cents":50}, {"name":"Extra rinse","add_cents":25}],
  "duration_min": 30, "input": 4 }
```

Dryer (price = one step of `dry_minutes`):

```json
{ "machine_no": 6, "relay": 6, "kind": "DRYER", "dry_minutes": 12, "dry_steps": 4 }
```

Single-pulse machine (fixed price, no options): `{ "machine_no": 1, "relay": 1, "pulse_count": 1 }`.
Leave `temps` / `addons` out for the defaults, `[]` for none. Also: `pulse_ms` (50), `gap_ms` (100),
`input` (module input wired to the machine's "in use" output, so a running machine can't be bought).

## Sources (installer guides, not manufacturer specs — confirm on site)

- Toronto prices: https://soda-laundry.ca/price-laundromat-self-service , https://sodalaundry.ca/laundry-service-pricing
- Ottawa dryer pricing: https://www.roundaboutlaundry.com/self-serve-laundromat/laundromat-pricing
- Per-cycle pricing and add-ons on coin-op washers: https://www.whirlpoolportal.com/whrnarstorefront/c/Commercial-Top-Load-Washer-with-Factory-Installed-Coin-Drop-and-Coin-Box/p/CAE2779JQ , https://pcrichard.com/whirlpool-27-in-3.1-cu-ft-commercial-front-load-washer-with-factory-installed-coin-drop-white/CHW9150GW.html
- Card readers (vend prices, dryer top-off, price options): https://support.laundroworks.com/portal/en/kb/articles/vend-prices , https://www.nayax.com/business_type/laundry

- Alliance Netmaster (SCxxN): https://support.laundroworks.com/portal/en/kb/articles/alliance-netmaster-washer-scxxn
- Speed Queen SCxxAC: https://support.laundroworks.com/portal/en/kb/articles/speed-queen-scxxac-washers-b-micro
- Milnor: https://support.laundroworks.com/portal/en/kb/articles/milnor-washers
- LG C5 harness (PayRange): https://payrange.com/for_download/manuals/PayRange-Laundry-Resources/Laundry-Installation-Guides/LG/Harness-C5-LG-C-PN-11.00902-xx.pdf
- LG Titan-C: https://cardconceptsinc.atlassian.net/wiki/x/eoayGQ
- LG Giant-C: https://docs.laundrycard.com/display/LCE/LG+Giant-C+Washer+Programming
- LG FAQ (connectors, voltages): https://www.lg.com/global/business/commercial-laundry/faqs/
- Laundrylux (PayRange): https://payrange.com/for_download/manuals/PayRange-Laundry-Resources/Laundry-Installation-Guides/Laundrylux/C19-PN-11.02064-Rev2.1.pdf
- Coin pulse widths: https://grobotronics.com/images/companies/1/PDF/6CoinOut.pdf
