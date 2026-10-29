import React, { useCallback, useEffect, useRef, useState } from 'react'
import { X, Loader2, CheckCircle2, AlertTriangle, Receipt } from 'lucide-react'

/*
 * Pay at the table: the table's live check (scan_bill), then all of it or a
 * share, a tip, and the card on Nuvei's own form (Simply Connect). What the
 * server says is the truth: amounts and tax come from scan_bill / scan-pay,
 * and "paid" is shown only when scan-pay says Nuvei confirmed it.
 * supabase/functions/scan-pay, docs/migrations/2026-10-28_scan_pay.sql.
 */

const NUVEI_SCRIPT = 'https://cdn.safecharge.com/safecharge_resources/v1/checkout/checkout.js'
// Tipping.kt: the same choices as Ourea / Eye / Iris, of the amount before tax.
const TIP_PERCENTS = [10, 12, 15]
const percentOf = (basis, bps) => Math.floor((basis * bps + 5000) / 10000)
const money = (c) => '$' + (c / 100).toFixed(2)
// Nuvei needs the customer's email for a card payment; it goes to Nuvei only.
// Remembered on this phone (and only here) so a second visit doesn't ask again.
const isEmail = (s) => s.length <= 100 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s)
const EMAIL_KEY = 'scan_receipt_email'
const savedEmail = () => { try { return localStorage.getItem(EMAIL_KEY) || '' } catch { return '' } }

let scriptPromise = null
function loadNuvei() {
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

/** The tax inside a payment of [amount] (same rule as the server and Ourea). */
function taxOf(check, amount) {
  if (amount >= check.balance_cents) return check.tax_left_cents
  if (check.balance_cents === 0) return 0
  return Math.floor((amount * check.tax_left_cents + Math.floor(check.balance_cents / 2)) / check.balance_cents)
}

export default function Bill({ token, T, supabase, callPay, onClose }) {
  const [bill, setBill] = useState(null)        // scan_bill result
  const [state, setState] = useState('loading') // loading | offline | ready | paying | confirming | paid | failed | review | pending
  const [mode, setMode] = useState('all')       // all | split | custom
  const [ways, setWays] = useState(2)
  const [custom, setCustom] = useState('')
  const [tip, setTip] = useState(null)          // null = not chosen yet
  const [customTip, setCustomTip] = useState(null)
  const [error, setError] = useState(null)
  const [session, setSession] = useState(null)
  const [balanceAfter, setBalanceAfter] = useState(null)
  const [email, setEmail] = useState(savedEmail)
  const confirmed = useRef(false)

  const load = useCallback(async () => {
    setState('loading')
    const { data, error: e } = await supabase.rpc('scan_bill', { p_token: token })
    if (e || !data) { setState('offline'); return }
    setBill(data); setState('ready')
    if (data.online_pay && data.check?.balance_cents > 0) loadNuvei().catch(() => {})  // ready by the time they tap Pay
  }, [supabase, token])
  useEffect(() => { load() }, [load])

  const check = bill?.check
  const balance = check?.balance_cents || 0
  const amount = !check ? 0
    : mode === 'all' ? balance
    : mode === 'split' ? Math.min(balance, Math.ceil(balance / ways))
    : Math.min(balance, Math.round(Number(custom || 0) * 100) || 0)
  const basis = check ? amount - taxOf(check, amount) : 0
  const maxTip = Math.max(basis, 2000)
  const tipCents = tip === 'custom' ? Math.min(Math.round(Number(customTip || 0) * 100) || 0, maxTip) : tip === 'none' ? 0 : tip ?? 0

  // A changed amount means a changed tip: choose again.
  useEffect(() => { if (tip !== null && tip !== 'none' && tip !== 'custom') setTip(null) }, [amount]) // eslint-disable-line react-hooks/exhaustive-deps

  const finish = async (id) => {
    if (confirmed.current) return
    confirmed.current = true
    setState('confirming')
    for (let i = 0; i < 20; i++) {
      const r = await callPay({ action: 'confirm', token, id })
      if (r.status === 'PAID') { setBalanceAfter(r.balance_cents ?? null); setState('paid'); return }
      if (r.status === 'FAILED') { setState('failed'); return }
      if (r.status === 'REVIEW') { setState('review'); return }
      await new Promise((ok) => setTimeout(ok, 3000))
    }
    setState('pending')
  }

  const pay = async () => {
    setError(null); confirmed.current = false
    setState('paying')
    try { localStorage.setItem(EMAIL_KEY, email.trim()) } catch { /* private mode: fine */ }
    const r = await callPay({ action: 'open', token, amount_cents: amount, tip_cents: tipCents, email: email.trim() })
    if (!r.session_token) {
      setState('ready')
      setError(T.billErrors[r.error] || T.billErrors.failed)
      if (r.error === 'bad_amount' || r.error === 'being_paid' || r.error === 'no_open_check') load()
      return
    }
    try {
      await loadNuvei()
    } catch {
      callPay({ action: 'cancel', token, id: r.id })
      setState('ready'); setError(T.billErrors.offline)
      return
    }
    setSession(r)
  }

  // Nuvei's card form, once its container is on screen.
  useEffect(() => {
    if (state !== 'paying' || !session || !window.checkout) return
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
        if (res?.cancelled) {
          callPay({ action: 'cancel', token, id: session.id })
          setSession(null); setState('ready')
        } else if (status === 'APPROVED') {
          finish(session.id)
        }
        // DECLINED / ERROR: Nuvei's form says so and lets the customer try another card.
      },
    })
  }, [state, session]) // eslint-disable-line react-hooks/exhaustive-deps

  const close = () => {
    // Leaving with the card form open: free the amount for the rest of the table.
    if (state === 'paying' && session) callPay({ action: 'cancel', token, id: session.id })
    onClose()
  }

  let body
  if (state === 'loading') {
    body = <div className="py-16 flex justify-center text-slate-400 gap-2"><Loader2 className="w-5 h-5 animate-spin" />{T.loading}</div>
  } else if (state === 'offline') {
    body = (
      <div className="py-12 text-center">
        <p className="font-bold text-slate-700">{T.offline}</p>
        <button onClick={load} className="mt-4 bg-slate-900 text-white font-bold px-6 py-3 rounded-full">{T.retry}</button>
      </div>
    )
  } else if (!check) {
    body = <p className="py-12 text-center font-bold text-slate-600">{T.noBill}</p>
  } else if (state === 'paid' || state === 'failed' || state === 'review' || state === 'pending' || state === 'confirming') {
    const icon = state === 'paid' ? <CheckCircle2 className="w-14 h-14 text-emerald-600" />
      : state === 'confirming' ? <Loader2 className="w-14 h-14 text-slate-400 animate-spin" />
      : <AlertTriangle className="w-14 h-14 text-amber-500" />
    const text = state === 'paid' ? T.paidOk(money(amount + tipCents))
      : state === 'confirming' ? T.confirming
      : state === 'failed' ? T.payFailed
      : state === 'review' ? T.payReview : T.payPending
    body = (
      <div className="py-10 flex flex-col items-center text-center gap-4">
        {icon}
        <p className="font-bold text-slate-800 text-lg">{text}</p>
        {state === 'paid' && balanceAfter > 0 && <p className="text-slate-600">{T.stillOwed(money(balanceAfter))}</p>}
        {state === 'paid' && balanceAfter === 0 && <p className="text-slate-600">{T.allPaid}</p>}
        {state === 'failed' && <button onClick={() => { setSession(null); load() }} className="bg-slate-900 text-white font-bold px-6 py-3 rounded-full">{T.retry}</button>}
        {(state === 'paid' && balanceAfter > 0) && <button onClick={() => { setSession(null); setTip(null); load() }} className="bg-slate-900 text-white font-bold px-6 py-3 rounded-full">{T.payMore}</button>}
      </div>
    )
  } else if (state === 'paying') {
    body = (
      <div className="py-3">
        <div className="flex justify-between font-black text-lg mb-3"><span>{T.payNow}</span><span>{money(amount + tipCents)}</span></div>
        <div id="nuvei-checkout" className="min-h-[320px]">
          {!session && <div className="py-16 flex justify-center text-slate-400"><Loader2 className="w-5 h-5 animate-spin" /></div>}
        </div>
      </div>
    )
  } else {
    body = (
      <>
        <div className="divide-y divide-slate-100">
          {check.lines.map((l, i) => (
            <div key={i} className="py-2 flex justify-between gap-3 text-sm">
              <span className="min-w-0">{l.quantity}× {l.name}{l.options?.length ? <span className="text-slate-500"> ({l.options.join(', ')})</span> : null}</span>
              <span className="font-bold shrink-0">{money(l.amount_cents)}</span>
            </div>
          ))}
        </div>
        <div className="mt-3 pt-3 border-t border-slate-200 space-y-1 text-sm text-slate-600">
          <div className="flex justify-between"><span>{T.subtotal}</span><span className="font-bold">{money(check.subtotal_cents)}</span></div>
          <div className="flex justify-between"><span>{T.taxActual}</span><span className="font-bold">{money(check.tax_cents)}</span></div>
          {check.paid_cents > 0 && <div className="flex justify-between"><span>{T.alreadyPaid}</span><span className="font-bold">−{money(check.paid_cents)}</span></div>}
          <div className="flex justify-between text-lg font-black text-slate-900 pt-1"><span>{T.balance}</span><span>{money(balance)}</span></div>
        </div>

        {!bill.online_pay ? (
          <p className="mt-5 text-center font-bold text-slate-600">{T.payServer}</p>
        ) : balance === 0 ? (
          <p className="mt-5 text-center font-bold text-emerald-700">{T.allPaid}</p>
        ) : (
          <div className="mt-5 space-y-5">
            <div>
              <h3 className="font-bold text-slate-900 mb-2">{T.howMuch}</h3>
              <div className="flex flex-wrap gap-2">
                {[['all', T.payAll], ['split', T.split], ['custom', T.customAmount]].map(([k, label]) => (
                  <button key={k} onClick={() => setMode(k)}
                    className={`px-4 py-2 rounded-full text-sm font-bold border ${mode === k ? 'bg-slate-900 text-white border-slate-900' : 'bg-white text-slate-700 border-slate-300'}`}>{label}</button>
                ))}
              </div>
              {mode === 'split' && (
                <div className="mt-3 flex items-center gap-2 flex-wrap">
                  {[2, 3, 4, 5, 6].map((n) => (
                    <button key={n} onClick={() => setWays(n)}
                      className={`w-11 h-11 rounded-full font-bold border ${ways === n ? 'bg-emerald-600 text-white border-emerald-600' : 'bg-white border-slate-300'}`}>{n}</button>
                  ))}
                  <span className="text-sm text-slate-600 ml-1">{T.perPerson(money(amount))}</span>
                </div>
              )}
              {mode === 'custom' && (
                <input inputMode="decimal" value={custom} placeholder={(balance / 100).toFixed(2)}
                  onChange={(e) => setCustom(e.target.value.replace(/[^0-9.]/g, '').slice(0, 8))}
                  className="mt-3 w-full px-4 py-3 rounded-xl border border-slate-300 text-lg" />
              )}
            </div>

            {amount > 0 && (
              <div>
                <h3 className="font-bold text-slate-900 mb-1">{T.tipTitle}</h3>
                <p className="text-xs text-slate-500 mb-2">{T.tipBasis(money(basis))}</p>
                <div className="grid grid-cols-3 gap-2">
                  {TIP_PERCENTS.map((pct) => {
                    const c = percentOf(basis, pct * 100)
                    return (
                      <button key={pct} onClick={() => setTip(c)}
                        className={`py-3 rounded-xl border font-bold ${tip === c ? 'bg-emerald-600 text-white border-emerald-600' : 'bg-white border-slate-300'}`}>
                        {pct}%<span className="block text-xs font-normal">{money(c)}</span>
                      </button>
                    )
                  })}
                  <button onClick={() => setTip('custom')}
                    className={`py-3 rounded-xl border font-bold ${tip === 'custom' ? 'bg-emerald-600 text-white border-emerald-600' : 'bg-white border-slate-300'}`}>{T.customTip}</button>
                  <button onClick={() => setTip('none')}
                    className={`col-span-2 py-3 rounded-xl border font-bold ${tip === 'none' ? 'bg-slate-900 text-white border-slate-900' : 'bg-white border-slate-300'}`}>{T.noTip}</button>
                </div>
                {tip === 'custom' && (
                  <input inputMode="decimal" value={customTip ?? ''} placeholder="0.00"
                    onChange={(e) => setCustomTip(e.target.value.replace(/[^0-9.]/g, '').slice(0, 7))}
                    className="mt-2 w-full px-4 py-3 rounded-xl border border-slate-300" />
                )}
              </div>
            )}

            {amount > 0 && (
              <div>
                <h3 className="font-bold text-slate-900 mb-1">{T.emailLabel}</h3>
                <input type="email" inputMode="email" autoComplete="email" value={email} placeholder="name@example.com"
                  onChange={(e) => setEmail(e.target.value.slice(0, 100))}
                  className="w-full px-4 py-3 rounded-xl border border-slate-300" />
                <p className="text-xs text-slate-500 mt-1">{T.emailHint}</p>
              </div>
            )}
          </div>
        )}
      </>
    )
  }

  const emailOk = isEmail(email.trim())
  const canPay = state === 'ready' && check && bill.online_pay && amount > 0 && tip !== null && emailOk
  return (
    <div className="fixed inset-0 z-50 bg-slate-900/60 flex items-end sm:items-center sm:justify-center">
      <div className="bg-white w-full max-w-lg rounded-t-3xl sm:rounded-3xl max-h-[92vh] flex flex-col">
        <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
          <h2 className="font-black text-xl flex items-center gap-2"><Receipt className="w-5 h-5" />{T.billTitle}{check ? ` · #${check.check_no}` : ''}</h2>
          <button onClick={close} className="p-2 text-slate-400"><X className="w-6 h-6" /></button>
        </div>
        <div className="px-5 py-3 overflow-y-auto flex-1">{body}</div>
        {state === 'ready' && check && bill.online_pay && balance > 0 && (
          <div className="p-5 bg-slate-50 border-t border-slate-200">
            {error && <p className="text-sm font-bold text-red-600 mb-2">{error}</p>}
            <button onClick={pay} disabled={!canPay}
              className="w-full bg-emerald-600 disabled:bg-slate-300 text-white font-black py-4 rounded-2xl text-lg">
              {tip === null && amount > 0 ? T.chooseTip : !emailOk ? T.enterEmail : T.payButton(money(amount + tipCents))}
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
