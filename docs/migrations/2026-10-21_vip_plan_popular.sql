-- 2026-10-21: one VIP load plan per store can carry a "MOST POPULAR" tag on the
-- terminal's VIP page (owner's request; set in Cael's VIP plans card). The
-- "BEST VALUE" tag stays computed (highest bonus ratio), this one is chosen.
BEGIN;

ALTER TABLE public.vip_load_plans ADD COLUMN IF NOT EXISTS is_popular BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN public.vip_load_plans.is_popular IS
    'Shown as MOST POPULAR on the terminal VIP page; at most one per org (vip_load_plans_one_popular).';
CREATE UNIQUE INDEX IF NOT EXISTS vip_load_plans_one_popular
    ON public.vip_load_plans (org_id) WHERE is_popular;

-- Same as before, plus is_popular.
CREATE OR REPLACE FUNCTION public.get_vip_load_plans()
 RETURNS json
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public'
AS $function$
DECLARE
  v_org_id UUID;
BEGIN
  SELECT org_id INTO v_org_id FROM public.device_auth_map WHERE auth_user_id = auth.uid();
  IF v_org_id IS NULL THEN
    RETURN '[]'::json;
  END IF;
  RETURN COALESCE((
    SELECT json_agg(json_build_object('id', id, 'amount_cents', amount_cents, 'bonus_cents', bonus_cents,
                                      'is_popular', is_popular)
                    ORDER BY sort_order, amount_cents)
    FROM public.vip_load_plans
    WHERE org_id = v_org_id AND is_active
  ), '[]'::json);
END;
$function$;

-- Eagleson: the $50 plan (owner, 2026-10-21).
UPDATE public.vip_load_plans SET is_popular = true, updated_at = now()
 WHERE org_id = '3c8e86c7-4c6d-4d1a-aa15-90aeed7aae76' AND amount_cents = 5000 AND is_active;

COMMIT;
