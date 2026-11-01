-- 2026-11-01: offline card sales (Iris / Ourea, opt-in per terminal).
--
-- With the store's say-so (Settings > Offline card payments, off by default, a
-- per-sale limit and a count limit), a card sale taken while the terminal has
-- no internet is sent to PAYWizard as "Deferred" (WIZARPOS Payment App
-- Integration Protocol 2.3.6+): approved on the terminal, sent to the bank
-- later. The store carries the risk: a card the bank refuses at upload was
-- never paid for.
--
-- offline_auth: approved offline. offline_status: PENDING_UPLOAD until the
-- terminal uploads its deferred sales (TransType "Deferred"), then UPLOADED --
-- or DECLINED, and then payment_status becomes DECLINED too (the money never
-- came), so the sale drops out of every sales figure; the receipt and the goods
-- already went, so the store has to follow up with the customer.

ALTER TABLE public.transactions
  ADD COLUMN IF NOT EXISTS offline_auth   BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS offline_status TEXT CHECK (offline_status IN ('PENDING_UPLOAD', 'UPLOADED', 'DECLINED'));

CREATE INDEX IF NOT EXISTS transactions_offline_pending_idx ON public.transactions (device_sn)
  WHERE offline_status = 'PENDING_UPLOAD';

NOTIFY pgrst, 'reload schema';
