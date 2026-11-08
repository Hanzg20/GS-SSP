-- 2026-11-08 (owner): a transaction's Details (the payment app's saved answers) and its remote
-- Void are for GoldSky administrators (SYS_ADMIN) only -- merchants see neither.
--
-- 1. The answers move out of transactions into transaction_payment_answers, readable by
--    SYS_ADMIN only (RLS). Terminals keep writing transactions.acquirer_response exactly as
--    before (Aegis Flex with the row, Wash through device_record_acquirer_response); a trigger
--    moves what they write into the new table and leaves the column NULL. Not a column REVOKE:
--    deployed terminals read their own rows with select=* and would fail (42501).
-- 2. device_commands: VOID_PAYMENT may only be queued by a SYS_ADMIN (the service role is
--    not checked). Merchants with devices.command keep every other command.

BEGIN;

CREATE TABLE IF NOT EXISTS public.transaction_payment_answers (
  tx_id      UUID PRIMARY KEY
             REFERENCES public.transactions(id) ON DELETE CASCADE
             DEFERRABLE INITIALLY DEFERRED, -- written by a BEFORE trigger, before the row exists
  answers    JSONB NOT NULL,                -- { "<TransType>": { ...sanitised answer... } }
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE public.transaction_payment_answers IS
  'Approved payment-app answers per transaction (moved from transactions.acquirer_response by trigger). SYS_ADMIN read only. 2026-11-08.';

ALTER TABLE public.transaction_payment_answers ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.transaction_payment_answers FROM anon, authenticated;
GRANT SELECT ON public.transaction_payment_answers TO authenticated;
DROP POLICY IF EXISTS "Sys admins read payment answers" ON public.transaction_payment_answers;
CREATE POLICY "Sys admins read payment answers" ON public.transaction_payment_answers
  FOR SELECT TO authenticated USING (public.is_sys_admin());

-- What is already there moves over.
INSERT INTO public.transaction_payment_answers (tx_id, answers)
SELECT id, acquirer_response FROM public.transactions
 WHERE acquirer_response IS NOT NULL AND jsonb_typeof(acquirer_response) = 'object'
ON CONFLICT (tx_id) DO UPDATE SET answers = transaction_payment_answers.answers || EXCLUDED.answers;
UPDATE public.transactions SET acquirer_response = NULL WHERE acquirer_response IS NOT NULL;

CREATE OR REPLACE FUNCTION public.tx_stash_acquirer_response() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF NEW.acquirer_response IS NOT NULL AND jsonb_typeof(NEW.acquirer_response) = 'object'
     AND NEW.acquirer_response <> '{}'::jsonb THEN
    INSERT INTO transaction_payment_answers (tx_id, answers) VALUES (NEW.id, NEW.acquirer_response)
    ON CONFLICT (tx_id) DO UPDATE
      SET answers = transaction_payment_answers.answers || EXCLUDED.answers, updated_at = now();
  END IF;
  NEW.acquirer_response := NULL;
  RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION public.tx_stash_acquirer_response() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS trg_tx_stash_acquirer_response ON public.transactions;
CREATE TRIGGER trg_tx_stash_acquirer_response
  BEFORE INSERT OR UPDATE OF acquirer_response ON public.transactions
  FOR EACH ROW EXECUTE FUNCTION public.tx_stash_acquirer_response();

COMMENT ON COLUMN public.transactions.acquirer_response IS
  'Write-only for terminals: moved by trigger to transaction_payment_answers (SYS_ADMIN read only), always NULL here. 2026-11-08.';

CREATE OR REPLACE FUNCTION public.guard_void_payment_command() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
BEGIN
  IF NEW.command = 'VOID_PAYMENT'
     AND coalesce(auth.role(), '') IN ('authenticated', 'anon')
     AND NOT public.is_sys_admin() THEN
    RAISE EXCEPTION 'VOID_PAYMENT is for GoldSky administrators only' USING ERRCODE = '42501';
  END IF;
  RETURN NEW;
END $$;
REVOKE ALL ON FUNCTION public.guard_void_payment_command() FROM PUBLIC, anon, authenticated;

DROP TRIGGER IF EXISTS trg_guard_void_payment_command ON public.device_commands;
CREATE TRIGGER trg_guard_void_payment_command
  BEFORE INSERT OR UPDATE OF command ON public.device_commands
  FOR EACH ROW EXECUTE FUNCTION public.guard_void_payment_command();

COMMIT;
