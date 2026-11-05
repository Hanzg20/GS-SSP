-- 2026-11-05: keep the payment app's whole answer for each approved card transaction (owner:
-- the full transaction id the bank knows was nowhere -- only PAYWizard's local TransID "73").
-- transactions.acquirer_response = { "<TransType>": { ...PAYWizard's response... } }, e.g.
-- {"PreAuth": {...}, "AuthCompletion": {...}}. Sanitised on the terminal first (PaymentResponses):
-- card number masked to first 6 / last 4, no track / PIN / CVV / expiry / signature fields.

BEGIN;

ALTER TABLE public.transactions ADD COLUMN IF NOT EXISTS acquirer_response JSONB;
COMMENT ON COLUMN public.transactions.acquirer_response IS
  'Approved payment-app answers by TransType (sanitised: masked card number, no track/PIN/CVV/expiry). 2026-11-05.';

-- A terminal adds an answer to its own row (Wash writes its row before the card is asked for);
-- no row yet = nothing done (Aegis Flex sends its answers with the row itself).
CREATE OR REPLACE FUNCTION public.device_record_acquirer_response(p_ref TEXT, p_type TEXT, p_response JSONB) RETURNS JSONB
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public AS $$
DECLARE v_sn TEXT; v_n INT;
BEGIN
  SELECT device_sn INTO v_sn FROM device_auth_map WHERE auth_user_id = auth.uid();
  IF v_sn IS NULL THEN RETURN jsonb_build_object('success', false, 'message', 'device_not_linked'); END IF;
  IF coalesce(p_ref, '') = '' OR coalesce(p_type, '') = '' OR jsonb_typeof(p_response) IS DISTINCT FROM 'object' THEN
    RETURN jsonb_build_object('success', false, 'message', 'invalid');
  END IF;
  UPDATE transactions
     SET acquirer_response = coalesce(acquirer_response, '{}'::jsonb) || jsonb_build_object(p_type, p_response)
   WHERE ecr_ref_num = p_ref AND device_sn = v_sn;
  GET DIAGNOSTICS v_n = ROW_COUNT;
  RETURN jsonb_build_object('success', true, 'updated', v_n);
END $$;

REVOKE ALL ON FUNCTION public.device_record_acquirer_response(TEXT, TEXT, JSONB) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.device_record_acquirer_response(TEXT, TEXT, JSONB) TO authenticated;

COMMIT;
