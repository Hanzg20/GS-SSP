import React from 'react'

/*
 * Card payment pieces shared by pay at the table (Bill.jsx, scan-pay) and Arke
 * online orders (ArkeApp.jsx, arke-pay): Nuvei's Simply Connect script, the tip
 * choices, the receipt email. Card data only ever goes to Nuvei.
 */

const NUVEI_SCRIPT = 'https://cdn.safecharge.com/safecharge_resources/v1/checkout/checkout.js'
// Tipping.kt: the same choices as Ourea / Eye / Iris, of the amount before tax.
export const TIP_PERCENTS = [10, 12, 15]
export const percentOf = (basis, bps) => Math.floor((basis * bps + 5000) / 10000)
const money = (c) => '$' + (c / 100).toFixed(2)
// Nuvei needs the customer's email for a card payment; it goes to Nuvei only.
// Remembered on this phone (and only here) so a second visit doesn't ask again.
export const isEmail = (s) => s.length <= 100 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s)
export const EMAIL_KEY = 'scan_receipt_email'
export const savedEmail = () => { try { return localStorage.getItem(EMAIL_KEY) || '' } catch { return '' } }
export const saveEmail = (e) => { try { localStorage.setItem(EMAIL_KEY, e) } catch { /* private mode: fine */ } }

let scriptPromise = null
export function loadNuvei() {
  if (window.checkout) return Promise.resolve()
  if (!scriptPromise) {
    scriptPromise = new Promise((ok, fail) => {
      const s = document.createElement('script')
      s.src = NUVEI_SCRIPT
      s.onload = () => (window.checkout ? ok() : fail(new Error('no checkout')))
      s.onerror = () => { scriptPromise = null; fail(new Error('script')) }
      document.head.appendChild(s)
    })
  }
  return scriptPromise
}

/**
 * Nuvei's card form into #nuvei-checkout for an opened session ({session_token, env, merchant_id, ...}).
 * onApproved / onCancelled; a decline is shown by Nuvei's own form, which lets the customer try another card.
 */
export function mountNuvei(session, T, { onApproved, onCancelled }) {
  window.checkout({
    sessionToken: session.session_token,
    env: session.env,
    merchantId: session.merchant_id,
    merchantSiteId: session.merchant_site_id,
    country: session.country,
    currency: session.currency,
    amount: Number(session.amount),
    locale: T.nuveiLocale,
    email: session.email,
    billingAddress: { country: session.country, email: session.email },
    pmWhitelist: ['cc_card'],
    renderTo: '#nuvei-checkout',
    onResult: (res) => {
      const status = String(res?.result || res?.transactionStatus || '').toUpperCase()
      if (res?.cancelled) onCancelled()
      else if (status === 'APPROVED') onApproved()
    },
  })
}

/** Tip: 10 / 12 / 15 % of [basis], a custom amount, or none. tip: cents | 'custom' | 'none' | null. */
export function TipPicker({ T, basis, tip, setTip, customTip, setCustomTip }) {
  return (
    <div>
      <h3 className="font-bold text-slate-900 mb-1">{T.tipTitle}</h3>
      <p className="text-xs text-slate-500 mb-2">{T.tipBasis(money(basis))}</p>
      <div className="grid grid-cols-3 gap-2">
        {TIP_PERCENTS.map((pct) => {
          const c = percentOf(basis, pct * 100)
          return (
            <button key={pct} onClick={() => setTip(c)}
              className={`py-3 rounded-xl border font-bold ${tip === c ? 'bg-gold-50 text-ink border-gold-500 border-2' : 'bg-white border-slate-300'}`}>
              {pct}%<span className="block text-xs font-normal">{money(c)}</span>
            </button>
          )
        })}
        <button onClick={() => setTip('custom')}
          className={`py-3 rounded-xl border font-bold ${tip === 'custom' ? 'bg-gold-50 text-ink border-gold-500 border-2' : 'bg-white border-slate-300'}`}>{T.customTip}</button>
        <button onClick={() => setTip('none')}
          className={`col-span-2 py-3 rounded-xl border font-bold ${tip === 'none' ? 'bg-ink text-white border-ink' : 'bg-white border-slate-300'}`}>{T.noTip}</button>
      </div>
      {tip === 'custom' && (
        <input inputMode="decimal" value={customTip ?? ''} placeholder="0.00"
          onChange={(e) => setCustomTip(e.target.value.replace(/[^0-9.]/g, '').slice(0, 7))}
          className="mt-2 w-full px-4 py-3 rounded-xl border border-slate-300" />
      )}
    </div>
  )
}

/** The tip in cents for a TipPicker choice. */
export const tipCentsOf = (tip, customTip, basis) =>
  tip === 'custom' ? Math.min(Math.round(Number(customTip || 0) * 100) || 0, Math.max(basis, 2000)) : tip === 'none' ? 0 : tip ?? 0

/** Receipt email (required by Nuvei). */
export function EmailField({ T, email, setEmail }) {
  return (
    <div>
      <h3 className="font-bold text-slate-900 mb-1">{T.emailLabel}</h3>
      <input type="email" inputMode="email" autoComplete="email" value={email} placeholder="name@example.com"
        onChange={(e) => setEmail(e.target.value.slice(0, 100))}
        className="w-full px-4 py-3 rounded-xl border border-slate-300" />
      <p className="text-xs text-slate-500 mt-1">{T.emailHint}</p>
    </div>
  )
}
