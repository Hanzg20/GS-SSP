// Uber Eats Marketplace: environment, access token, the order as we keep it (docs/uber_eats_design.md).
// Env: UBER_CLIENT_ID, UBER_CLIENT_SECRET (Supabase secrets, set by the owner), UBER_ENV =
// "sandbox" (default) | "production", UBER_WEBHOOK_SECRET (optional; Uber signs webhooks with the
// client secret unless the app was given its own signing key). The token and API domains must match:
// sandbox-login.uber.com + test-api.uber.com, or auth.uber.com + api.uber.com.

import { createClient, SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

export const UBER_ENV = (Deno.env.get("UBER_ENV") ?? "sandbox").toLowerCase() === "production" ? "production" : "sandbox";
export const UBER_AUTH = UBER_ENV === "production" ? "https://auth.uber.com" : "https://sandbox-login.uber.com";
export const UBER_API = UBER_ENV === "production" ? "https://api.uber.com" : "https://test-api.uber.com";
export const UBER_SCOPE = "eats.order eats.store";

export function serviceClient(): SupabaseClient {
  return createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, { auth: { persistSession: false } });
}

export interface TokenResult { token?: string; scope?: string; expires_in?: number; http?: number; error?: string; error_description?: string }

/** A token straight from Uber (and cached for everyone). */
export async function freshToken(db: SupabaseClient | null, scope = UBER_SCOPE): Promise<TokenResult> {
  const id = Deno.env.get("UBER_CLIENT_ID"), secret = Deno.env.get("UBER_CLIENT_SECRET");
  if (!id || !secret) return { error: "UBER_CLIENT_ID / UBER_CLIENT_SECRET not set" };
  const res = await fetch(`${UBER_AUTH}/oauth/v2/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ client_id: id, client_secret: secret, grant_type: "client_credentials", scope }),
    signal: AbortSignal.timeout(8000),
  });
  const text = await res.text();
  let data: Record<string, unknown> = {};
  try { data = JSON.parse(text); } catch { /* not JSON */ }
  if (!res.ok || typeof data.access_token !== "string") {
    return { http: res.status, error: String(data.error ?? text.slice(0, 300)), error_description: data.error_description as string | undefined };
  }
  const r: TokenResult = { token: data.access_token, scope: data.scope as string, expires_in: Number(data.expires_in ?? 0) };
  // Only a token carrying both scopes is shared: a narrower test token must not replace it.
  if (db && scope === UBER_SCOPE) {
    const { error } = await db.rpc("online_uber_token_save", { p_env: UBER_ENV, p_token: r.token, p_scope: r.scope ?? scope, p_expires_in: r.expires_in });
    if (error) console.error("[uber] token not cached:", error.message);
  }
  return r;
}

/** The cached token while it has more than a day left, else a new one. */
export async function uberToken(db: SupabaseClient): Promise<string | null> {
  const { data } = await db.rpc("online_uber_token", { p_env: UBER_ENV });
  if (data?.access_token) return data.access_token as string;
  const r = await freshToken(db);
  if (!r.token) console.error(`[uber] no token: ${r.http ?? ""} ${r.error} ${r.error_description ?? ""}`);
  return r.token ?? null;
}

/** X-Uber-Signature: HMAC-SHA256 of the raw body, lowercase hex. Constant-time compare. */
export async function signatureOk(raw: string, given: string | null): Promise<boolean> {
  const key = Deno.env.get("UBER_WEBHOOK_SECRET") || Deno.env.get("UBER_CLIENT_SECRET");
  if (!key || !given) return false;
  const k = await crypto.subtle.importKey("raw", new TextEncoder().encode(key), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", k, new TextEncoder().encode(raw)));
  const want = Array.from(mac, (b) => b.toString(16).padStart(2, "0")).join("");
  const got = given.trim().toLowerCase();
  if (got.length !== want.length) return false;
  let diff = 0;
  for (let i = 0; i < want.length; i++) diff |= want.charCodeAt(i) ^ got.charCodeAt(i);
  return diff === 0;
}

// ---------------------------------------------------------------- Uber's order -> ours

// deno-lint-ignore no-explicit-any
type Any = any;

/** Cents from Uber's money object: v2 {amount_e5}, v1 {amount, formatted_amount}, or a bare number of cents. */
export function cents(m: Any): number | null {
  if (m == null) return null;
  if (typeof m === "number") return Math.round(m);
  if (typeof m.amount_e5 === "number") return Math.round(m.amount_e5 / 1000);
  if (typeof m.formatted_amount === "string") {
    const n = Number(m.formatted_amount.replace(/[^0-9.\-]/g, ""));
    if (Number.isFinite(n) && m.formatted_amount.match(/[0-9]/)) return Math.round(n * 100);
  }
  if (typeof m.amount === "number") return Number.isInteger(m.amount) ? m.amount : Math.round(m.amount * 100);
  if (m.gross) return cents(m.gross);
  return null;
}

const qty = (q: Any): number => {
  const n = typeof q === "number" ? q : Number(q?.amount ?? 1);
  return Number.isFinite(n) && n > 0 ? Math.round(n) : 1;
};

/** Options under a dish, nested groups flattened ("Size: Large", "2 × Extra cheese"). */
function optionsOf(groups: Any[] | undefined, out: string[] = []): string[] {
  for (const g of groups ?? []) {
    for (const s of g?.selected_items ?? []) {
      const n = qty(s.quantity);
      const title = String(s.title ?? s.name ?? "").trim();
      if (title) out.push(n > 1 ? `${n} × ${title}` : title);
      optionsOf(s.selected_modifier_groups, out);
    }
  }
  return out;
}

export interface OurOrder {
  id: string; display_id: string | null; type: string | null; customer_name: string | null; customer_phone: string | null;
  note: string | null; placed_at: string | null; subtotal_cents: number; tax_cents: number;
  items: { name: string; options: string[]; quantity: number; unit_price_cents: number; note: string | null }[];
}

/** GET order answer (v1 flat, or v2 { order: {...} }) -> what online_uber_order_in takes. */
export function toOurOrder(body: Any): OurOrder | null {
  const o = body?.order ?? body;
  if (!o || typeof o.id !== "string") return null;
  const cartItems: Any[] = o.cart?.items ?? (o.carts ?? []).flatMap((c: Any) => c?.items ?? []);
  const items = cartItems.map((i: Any) => {
    const n = qty(i.quantity);
    const unit = cents(i.price?.unit_price ?? i.price?.unit) ?? (cents(i.price?.total_price) != null ? Math.round(cents(i.price.total_price)! / n) : 0);
    return {
      name: String(i.title ?? i.name ?? "?"),
      options: optionsOf(i.selected_modifier_groups),
      quantity: n,
      unit_price_cents: unit ?? 0,
      note: (i.special_instructions ?? i.customer_request?.special_instructions ?? null) || null,
    };
  });
  const eater = o.eater ?? o.customers?.[0] ?? {};
  const name = eater.first_name
    ? [eater.first_name, eater.last_name ? String(eater.last_name)[0] + "." : ""].join(" ").trim()
    : (eater.name?.display_name ?? eater.name?.first_name ?? null);
  const phoneObj = eater.contact?.phone ?? {};
  const phone = [eater.phone ?? phoneObj.number, eater.phone_code ?? phoneObj.pin_code].filter(Boolean).join(" / ") || null;
  const charges = o.payment?.charges ?? {};
  const detail = o.payment?.payment_detail ?? {};
  const itemsTotal = items.reduce((s: number, i) => s + i.unit_price_cents * i.quantity, 0);
  const subtotal = cents(charges.sub_total) ?? cents(detail.item_charges?.subtotal_including_promos) ?? itemsTotal;
  const tax = cents(charges.tax) ?? cents(detail.tax?.total) ?? cents(detail.item_charges?.total_tax) ?? 0;
  return {
    id: o.id,
    display_id: o.display_id ?? null,
    type: o.type ?? o.fulfillment_type ?? null,
    customer_name: name,
    customer_phone: phone,
    note: (o.cart?.special_instructions ?? o.carts?.[0]?.special_instructions ?? null) || null,
    placed_at: o.placed_at ?? o.created_time ?? null,
    subtotal_cents: subtotal,
    tax_cents: tax,
    items,
  };
}
