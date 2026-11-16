import { createClient } from '@supabase/supabase-js'

/** Shared by the table page (Ourea Scan) and online ordering (Arke): the backend, money, this phone's storage. */

export const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL || 'https://ttbtxxpnvkcbyugzdqfw.supabase.co'
export const SUPABASE_KEY = import.meta.env.VITE_SUPABASE_KEY || 'sb_publishable_g4TGLHlVn3LfafRZFOAjnA_x9M0xyBi'
export const supabase = createClient(SUPABASE_URL, SUPABASE_KEY)

/**
 * POST to one of our Edge Functions; never throws ({error: 'offline'} when the network is down).
 * A non-2xx answer becomes {error}; [raw] hands back the function's own body whatever the status
 * (scan-pay answers its refusals that way).
 */
export async function callFunction(name, body, { raw = false } = {}) {
  try {
    const res = await fetch(`${SUPABASE_URL}/functions/v1/${name}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', apikey: SUPABASE_KEY },
      body: JSON.stringify(body),
    })
    if (raw) return await res.json().catch(() => ({ error: 'failed' }))
    const data = await res.json().catch(() => ({}))
    return res.ok ? data : { error: data.error || 'failed' }
  } catch {
    return { error: 'offline' }
  }
}

/** The AI guide (supabase/functions/scan-assistant). */
export const askGuide = (body) => callFunction('scan-assistant', body)

export const money = (c) => '$' + (c / 100).toFixed(2)

export const store = {
  get(k) { try { return JSON.parse(localStorage.getItem(k) || 'null') } catch { return null } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)) } catch { /* private mode: fine */ } },
}

export const newRef = () => (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2))
