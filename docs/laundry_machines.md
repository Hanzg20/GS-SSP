# Aegis Laundry — connecting coin-op machines (2026-10-14)

One Q3mini drives up to 8 machines through an RS485 Modbus RTU relay module (RS232→RS485
converter on the terminal's serial port; module at 9600 8N1, slave 1). Each relay closes
across the machine's **coin-drop input** ("Coin 1"), exactly as a coin mechanism or a
third-party card reader does. Code: `core/hardware/.../modbus/ModbusRelayBoard.kt`,
`feature/laundry/LaundryMachineConfig.kt`.

## Recommended: single-pulse start

Card-reader vendors (PayRange, LaundroWorks, Card Concepts, Cents) set the machine so that
**one pulse starts a full cycle** and keep the price on the payment side. Then:

- the customer's price is the product price in Cael (can change any time, no visit to the machine);
- the product gets `"pulse_count": 1`;
- on the machine: a single-pulse start mode, or the vend price set to one coin.

Only when that isn't possible, send coins: leave `pulse_count` out and set `coin_cents`
to the machine's coin value; the terminal then sends price ÷ coin pulses (a price that
isn't whole coins is refused).

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

```json
{ "machine_no": 3, "relay": 3, "kind": "WASHER", "pulse_count": 1, "pulse_ms": 50, "gap_ms": 100, "duration_min": 32, "input": 3 }
```

`input` (optional): module input wired to the machine's "in use" output, so a running
machine can't be bought.

## Sources (installer guides, not manufacturer specs — confirm on site)

- Alliance Netmaster (SCxxN): https://support.laundroworks.com/portal/en/kb/articles/alliance-netmaster-washer-scxxn
- Speed Queen SCxxAC: https://support.laundroworks.com/portal/en/kb/articles/speed-queen-scxxac-washers-b-micro
- Milnor: https://support.laundroworks.com/portal/en/kb/articles/milnor-washers
- LG C5 harness (PayRange): https://payrange.com/for_download/manuals/PayRange-Laundry-Resources/Laundry-Installation-Guides/LG/Harness-C5-LG-C-PN-11.00902-xx.pdf
- LG Titan-C: https://cardconceptsinc.atlassian.net/wiki/x/eoayGQ
- LG Giant-C: https://docs.laundrycard.com/display/LCE/LG+Giant-C+Washer+Programming
- LG FAQ (connectors, voltages): https://www.lg.com/global/business/commercial-laundry/faqs/
- Laundrylux (PayRange): https://payrange.com/for_download/manuals/PayRange-Laundry-Resources/Laundry-Installation-Guides/Laundrylux/C19-PN-11.02064-Rev2.1.pdf
- Coin pulse widths: https://grobotronics.com/images/companies/1/PDF/6CoinOut.pdf
