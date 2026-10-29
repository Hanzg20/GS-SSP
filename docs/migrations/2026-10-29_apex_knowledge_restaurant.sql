-- 2026-10-29: 小海狸 (Apex) GoldSky-wide knowledge brought up to date (owner:
-- "相关ai 知识库也要丰富一下"). The Cael menu is named Cael and its groups are now
-- Payments / Devices / Admin; restaurant modes, dine-in, QR ordering, pay at the
-- table, online refunds and day close are new since 2026-10-10. Only facts the
-- shipped apps do today. Re-runnable: updates by sort_order, inserts skip a
-- sort_order that is already there.

BEGIN;

UPDATE apex_knowledge SET updated_at = now(), content =
  'Staff (name, role, 4-digit PIN) are added and reset in Cael under Admin > Staff. Roles: CASHIER, MANAGER (also sees reports, settings, refund approval), DRIVER (delivery collection only).'
 WHERE org_id IS NULL AND sort_order = 30;
UPDATE apex_knowledge SET updated_at = now(), content =
  'Delivery orders are entered in Cael (Payments > Delivery Orders). The driver collects card or cash on the handheld; the order then shows as paid.'
 WHERE org_id IS NULL AND sort_order = 50;
UPDATE apex_knowledge SET updated_at = now(), content =
  'Products, prices, barcodes and the tax rate per product are managed in Cael (Devices > Retail Products), including bulk import. Terminals pick up changes on their next sync. Discounts are set up in Devices > Discounts.'
 WHERE org_id IS NULL AND sort_order = 60;
UPDATE apex_knowledge SET updated_at = now(), content =
  'The day report (managers) shows cash, card and "Online (QR)" payments made by customers at the table, sales by staff, tax and tips, and refunds/voids for one day, and can be printed.'
 WHERE org_id IS NULL AND sort_order = 80;

INSERT INTO apex_knowledge (org_id, sort_order, content)
SELECT NULL, v.sort_order, v.content
  FROM (VALUES
    (90,  'Cael (cael.si-sky.com) is the store''s web back office (older notes call it CMP). Ourea is the counter POS, Iris the handheld.'),
    (100, 'On Ourea, Settings > Business type picks how the store sells: Retail store (scan and sell, no kitchen), Quick-service restaurant, or Dine-in restaurant.'),
    (110, 'Quick-service restaurant: each order is dine-in or take-out, can carry an order note, gets the day''s call number, and prints a kitchen ticket.'),
    (120, 'Kitchen tickets go to the network kitchen printer set in Ourea Settings > Kitchen printer (its IP address; ESC/POS printer on port 9100; Test print checks it). With no printer set, tickets print on the terminal itself.'),
    (130, 'Dine-in tables are added in Ourea Settings > Tables, or in Cael under Devices > Dine-in / Scan Order, where each table''s QR card can also be printed.'),
    (140, 'Dine-in: staff seat a table with the number of guests, take the order in rounds and send each round to the kitchen, print the bill, then take payment for the whole check, by items, or split evenly. Voiding a dish already sent to the kitchen needs a manager''s PIN.'),
    (150, 'QR ordering (Ourea Scan): customers scan the QR card on their table and order on their phone. The order waits on the floor screen (banner and chime) until staff accept it on Ourea / Iris; only then does it go to the kitchen as the table''s next round.'),
    (160, 'Pay at the table: from the same QR page the customer taps Bill and pays the whole bill, an even share, or an amount of their choice by card on their phone, plus a tip (10%, 12% or 15% of the amount before tax, a custom amount, or none). An email address is required for the card receipt. The table counts as paid only once the card processor confirms it.'),
    (170, 'When a customer pays at the table, the floor screen shows a green banner with a chime (e.g. "T3 paid online $26.94, table settled"), and the table card shows what is still owed. A fully paid table closes by itself.'),
    (180, 'Pay at the table needs the store''s online card account (Nuvei) to be set up by GoldSky. If customers can order by QR but cannot pay, contact GoldSky support.'),
    (190, 'An online (QR) payment is refunded on Ourea: Transactions, open the payment, Refund, with a manager''s PIN. It is the whole payment. On the same day it is cancelled before it settles; later it goes back to the customer''s card. On a table that is still open, a refunded payment is owed again.'),
    (200, 'Store expenses (stock purchases, rent, utilities, payroll, repairs, marketing, fees, other) are recorded in Cael under Payments > Store expenses, or by telling Beaver on Ourea / Iris, e.g. "paid the hydro bill 234.50 today"; Beaver shows a card to confirm before anything is saved.'),
    (210, 'Reminders: tell Beaver on Ourea / Iris, e.g. "remind me at 9 tomorrow to order stock"; after confirming, it shows on the day it is due, for one person or for everyone.'),
    (220, 'Tips are a percentage of the amount before tax (after discounts), a custom amount, or none, chosen by the customer. On Ourea with an Ourea Eye customer display the customer picks it there and the cashier only sees that they are choosing; without one, the tip panel opens on Ourea for the screen to be turned to the customer. Settings > Ask for tip turns the tip screen on or off.')
  ) AS v(sort_order, content)
 WHERE NOT EXISTS (SELECT 1 FROM apex_knowledge k WHERE k.org_id IS NULL AND k.sort_order = v.sort_order);

COMMIT;
