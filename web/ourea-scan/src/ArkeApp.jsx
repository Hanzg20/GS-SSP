import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Plus, Minus, X, ChevronRight, Loader2, Edit3, Phone, MapPin, Clock, CheckCircle2, AlertTriangle, ClipboardList, Menu as MenuIcon, Globe, Check } from 'lucide-react'
import { T, LANG, LANGS, pickLang, dishName } from './i18n.js'
import { AK } from './arke-i18n.js'
import { supabase, callFunction, money, store, newRef } from './lib.js'
import { Beaver, DishImg, Sheet, DishSheet, AvoidSheet, BeaverScreen, Signature, CategorySheet,
  useMenuSections, useCategoryTabs, MenuFilters, DishRow, MenuSections } from './shared.jsx'
import { isEmail, savedEmail, saveEmail, loadNuvei, mountNuvei, TipPicker, tipCentsOf, EmailField } from './pay.jsx'

/*
 * Arke -- online ordering for pickup (owner 2026-11-16, phase 1 of
 * docs/online_ordering_design.md). The store's link is <this site>/?s=<slug>;
 * the customer builds a cart on this phone, places the order (online_place_order),
 * pays it on Nuvei's form (arke-pay) and follows it on its own page (?o=<token>):
 * paid -> the store accepts (ready time) -> ready -> picked up. The server prices
 * everything; "paid" only when arke-pay says Nuvei confirmed it.
 * docs/migrations/2026-11-16_arke_online_orders.sql.
 */

const callPay = (body) => callFunction('arke-pay', body, { raw: true })
const ACTIVE = ['AWAITING_PAYMENT', 'NEW', 'ACCEPTED', 'READY']

/** The orders this phone placed (status links), newest first. */
const myOrders = {
  list() { return store.get('arke_orders') || [] },
  add(o) { store.set('arke_orders', [o, ...myOrders.list().filter((x) => x.token !== o.token)].slice(0, 10)) },
}

const timeOf = (iso, tz) => {
  try { return new Date(iso).toLocaleTimeString(LANG, { hour: 'numeric', minute: '2-digit', timeZone: tz || undefined }) } catch { return '' }
}

function StoreLogo({ s, size = 'w-12 h-12 text-xl' }) {
  const [broken, setBroken] = useState(false)
  return s.logo_url && !broken
    ? <img src={s.logo_url} alt="" onError={() => setBroken(true)} className={`${size} rounded-2xl object-cover bg-white shadow-card shrink-0`} />
    : <div className={`${size} rounded-2xl bg-gradient-to-br from-gold-400 to-gold-600 text-ink font-black flex items-center justify-center shrink-0`}>{s.name.trim().charAt(0)}</div>
}

/** Language and the orders this phone placed. */
function MenuSheet({ onClose }) {
  const orders = myOrders.list()
  return (
    <Sheet onClose={onClose}>
      <div className="px-5 pt-5 pb-6 space-y-5 overflow-y-auto">
        <div className="flex items-center justify-between">
          <h2 className="font-black text-xl text-ink">{T.menuTitle}</h2>
          <button onClick={onClose} aria-label={T.close} className="p-1 text-ink-mute"><X className="w-6 h-6" /></button>
        </div>
        <div>
          <p className="text-sm font-black text-ink-soft mb-2 flex items-center gap-1"><Globe className="w-4 h-4" />{T.language}</p>
          <div className="grid grid-cols-2 gap-3">
            {LANGS.map(([l, label]) => (
              <button key={l} onClick={() => pickLang(l)} className={`py-3 rounded-2xl font-black border-2 ${l === LANG ? 'bg-ink text-white border-ink' : 'bg-white text-ink-soft border-slate-200'}`}>{label}</button>
            ))}
          </div>
        </div>
        {orders.length > 0 && (
          <div>
            <p className="text-sm font-black text-ink-soft mb-2 flex items-center gap-1"><ClipboardList className="w-4 h-4" />{AK.myOrders}</p>
            <div className="space-y-2">
              {orders.map((o) => (
                <a key={o.token} href={`?o=${o.token}`} className="flex items-center justify-between px-4 py-3 rounded-2xl bg-paper border border-slate-200">
                  <span className="font-bold text-ink">{o.store}{o.code ? ` · ${o.code}` : ''}</span>
                  <span className="text-xs text-ink-mute">{new Date(o.at).toLocaleDateString(LANG)}</span>
                </a>
              ))}
            </div>
          </div>
        )}
        <Signature product="Arke" />
      </div>
    </Sheet>
  )
}

/** Cart -> who picks it up -> tip -> email -> Nuvei's card form -> the order's page. */
function Checkout({ slug, menu, lines, subtotal, tax, onEdit, onClose, onPaid }) {
  const [info, setInfo] = useState(() => store.get('arke_customer') || { name: '', phone: '' })
  const [note, setNote] = useState('')
  const [tip, setTip] = useState(null)
  const [customTip, setCustomTip] = useState(null)
  const [email, setEmail] = useState(savedEmail)
  const [state, setState] = useState('form')   // form | placing | paying | confirming | failed | review | pending
  const [error, setError] = useState(null)
  const [session, setSession] = useState(null)
  const [order, setOrder] = useState(null)      // {id, token, subtotal_cents, tax_cents} once placed
  const clientRef = useRef(newRef())            // one checkout = one order, across retries
  const confirmed = useRef(false)
  const s = menu.store

  useEffect(() => { loadNuvei().catch(() => {}) }, [])   // ready by the time they tap Pay

  const tipCents = tipCentsOf(tip, customTip, subtotal)
  const phoneOk = (info.phone || '').replace(/[^0-9]/g, '').length >= 10
  const ready = info.name.trim() && phoneOk && tip !== null && isEmail(email.trim())

  const finish = async (id, token) => {
    if (confirmed.current) return
    confirmed.current = true
    setState('confirming')
    for (let i = 0; i < 20; i++) {
      const r = await callPay({ action: 'confirm', token, id })
      if (r.status === 'PAID') { onPaid(token, r.pickup_code); return }
      if (r.status === 'FAILED') { setState('failed'); return }
      if (r.status === 'REVIEW') { setState('review'); return }
      await new Promise((ok) => setTimeout(ok, 3000))
    }
    setState('pending')
  }

  const pay = async () => {
    setError(null); confirmed.current = false
    store.set('arke_customer', { name: info.name.trim(), phone: info.phone.trim() })
    saveEmail(email.trim())
    setState('placing')
    let o = order
    if (!o) {
      const { data, error: e } = await supabase.rpc('online_place_order', {
        p_slug: slug, p_items: lines.map((l) => ({ product_id: l.product_id, option_ids: l.option_ids, note: l.note || null, quantity: l.quantity })),
        p_name: info.name.trim(), p_phone: info.phone.trim(), p_note: note.trim() || null, p_client_ref: clientRef.current,
      })
      if (e) { setState('form'); setError(AK.errors.offline); return }
      if (!data?.success) {
        setState('form')
        setError((AK.errors[data?.message] || AK.errors.failed) + (data?.product ? data.product : ''))
        return
      }
      o = data
      setOrder(data)
      myOrders.add({ token: data.token, store: s.name, at: new Date().toISOString() })
    }
    const r = await callPay({ action: 'open', token: o.token, tip_cents: tipCents, email: email.trim() })
    if (!r.session_token) {
      setState('form'); setError(AK.errors[r.error] || AK.errors.failed)
      if (r.error === 'order_expired') { setOrder(null); clientRef.current = newRef() }
      if (r.error && r.error.startsWith('order_') && r.error !== 'order_expired') onPaid(o.token)   // already paid elsewhere: show its page
      return
    }
    try {
      await loadNuvei()
    } catch {
      callPay({ action: 'cancel', token: o.token, id: r.id })
      setState('form'); setError(AK.errors.offline)
      return
    }
    setSession({ ...r, token: o.token })
    setState('paying')
  }

  useEffect(() => {
    if (state !== 'paying' || !session || !window.checkout) return
    mountNuvei(session, T, {
      onApproved: () => finish(session.id, session.token),
      onCancelled: () => { callPay({ action: 'cancel', token: session.token, id: session.id }); setSession(null); setState('form') },
    })
  }, [state, session]) // eslint-disable-line react-hooks/exhaustive-deps

  const close = () => {
    if (state === 'paying' && session) callPay({ action: 'cancel', token: session.token, id: session.id })
    onClose()
  }

  let body
  if (state === 'paying') {
    body = (
      <div className="py-3">
        <div className="flex justify-between font-black text-lg mb-3"><span>{T.payNow}</span><span>{money(subtotal + tax + tipCents)}</span></div>
        <div id="nuvei-checkout" className="min-h-[320px]" />
      </div>
    )
  } else if (state === 'confirming' || state === 'failed' || state === 'review' || state === 'pending') {
    body = (
      <div className="py-10 flex flex-col items-center text-center gap-4">
        {state === 'confirming' ? <Loader2 className="w-14 h-14 text-slate-400 animate-spin" /> : <AlertTriangle className="w-14 h-14 text-amber-500" />}
        <p className="font-bold text-slate-800 text-lg">{state === 'confirming' ? T.confirming : state === 'failed' ? T.payFailed : state === 'review' ? AK.payReview : AK.payPending}</p>
        {state === 'failed' && <button onClick={() => { setSession(null); setState('form') }} className="btn-gold px-6 py-3 rounded-full">{T.retry}</button>}
        {(state === 'review' || state === 'pending') && order && <a href={`?o=${order.token}`} className="btn-gold px-6 py-3 rounded-full">{AK.myOrder}</a>}
      </div>
    )
  } else {
    body = (
      <div className="space-y-5 py-2">
        <div>
          <div className="flex items-center justify-between mb-1">
            <h3 className="font-black text-ink">{AK.cart}</h3>
            <button onClick={onEdit} className="text-sm font-bold text-sky-700 flex items-center gap-1"><Edit3 className="w-4 h-4" />{T.orderMoreBtn}</button>
          </div>
          <div className="divide-y divide-slate-100">
            {lines.map((l) => (
              <div key={l.key} className="py-2 flex justify-between gap-3 text-sm">
                <span className="min-w-0">{l.quantity}× {l.p ? dishName(l.p) : '…'}{l.optionNames.length ? <span className="text-slate-500"> ({l.optionNames.join(', ')})</span> : null}</span>
                <span className="font-bold shrink-0">{money(l.unit * l.quantity)}</span>
              </div>
            ))}
          </div>
          <div className="mt-2 pt-2 border-t border-slate-200 space-y-1 text-sm text-slate-600">
            <div className="flex justify-between"><span>{T.subtotal}</span><span className="font-bold">{money(subtotal)}</span></div>
            <div className="flex justify-between"><span>{T.taxActual}</span><span className="font-bold">{money(tax)}</span></div>
          </div>
        </div>
        <div>
          <h3 className="font-black text-ink mb-2">{AK.yourInfo}</h3>
          <div className="space-y-2">
            <input value={info.name} onChange={(e) => setInfo({ ...info, name: e.target.value.slice(0, 40) })} placeholder={AK.name} autoComplete="name"
              className="w-full px-4 py-3 rounded-xl border border-slate-300" />
            <input value={info.phone} onChange={(e) => setInfo({ ...info, phone: e.target.value.slice(0, 20) })} placeholder={AK.phone}
              type="tel" inputMode="tel" autoComplete="tel" className="w-full px-4 py-3 rounded-xl border border-slate-300" />
            <p className="text-xs text-slate-500">{AK.phoneHint}</p>
          </div>
        </div>
        <div>
          <h3 className="font-black text-ink mb-2">{AK.orderNote}</h3>
          <input value={note} onChange={(e) => setNote(e.target.value.slice(0, 200))} placeholder={AK.orderNotePh}
            className="w-full px-4 py-3 rounded-xl border border-slate-300" />
        </div>
        <TipPicker T={T} basis={subtotal} tip={tip} setTip={setTip} customTip={customTip} setCustomTip={setCustomTip} />
        <EmailField T={T} email={email} setEmail={setEmail} />
      </div>
    )
  }

  return (
    <div className="fixed inset-0 z-50 bg-ink/60 flex items-end sm:items-center sm:justify-center">
      <div className="bg-white w-full max-w-lg rounded-t-3xl sm:rounded-3xl max-h-[92vh] flex flex-col">
        <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
          <h2 className="font-black text-xl">{AK.checkout} · {AK.pickup}</h2>
          <button onClick={close} aria-label={T.close} className="p-2 text-slate-400"><X className="w-6 h-6" /></button>
        </div>
        <div className="px-5 py-3 overflow-y-auto flex-1">{body}</div>
        {(state === 'form' || state === 'placing') && (
          <div className="p-5 bg-slate-50 border-t border-slate-200">
            {error && <p className="text-sm font-bold text-red-600 mb-2">{error}</p>}
            <button onClick={pay} disabled={!ready || state === 'placing'} className="btn-gold w-full py-4 rounded-2xl text-lg flex items-center justify-center gap-2">
              {state === 'placing' && <Loader2 className="w-5 h-5 animate-spin" />}
              {state === 'placing' ? AK.placing
                : !info.name.trim() || !phoneOk ? AK.fillInfo
                : tip === null ? T.chooseTip
                : !isEmail(email.trim()) ? T.enterEmail
                : AK.payAndOrder(money(subtotal + tax + tipCents))}
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

/** The order's own page: pickup number, where it is, the store's address and phone. */
function OrderPage({ token }) {
  const [o, setO] = useState(undefined)   // undefined loading, null not found
  const [offline, setOffline] = useState(false)
  const read = useCallback(async () => {
    const { data, error } = await supabase.rpc('online_order_status', { p_token: token })
    if (error) { setOffline(true); return }
    setOffline(false); setO(data)
    if (data) document.title = `${data.store.name} · ${data.pickup_code || AK.myOrder}`
  }, [token])
  useEffect(() => { read() }, [read])
  useEffect(() => {
    if (!o || !ACTIVE.includes(o.status)) return
    const id = setInterval(() => { if (document.visibilityState === 'visible') read() }, 8000)
    return () => clearInterval(id)
  }, [o, read])

  if (o === undefined) return offline
    ? <BeaverScreen mood="alert" text={T.offline} action={<button onClick={read} className="btn-gold mt-6 px-8 py-3.5 rounded-2xl">{T.retry}</button>} />
    : <BeaverScreen mood="blink" text={T.loading} />
  if (o === null) return <BeaverScreen mood="alert" text={AK.noStore} />

  const s = o.store
  const step = { NEW: 1, ACCEPTED: 2, READY: 3, PICKED_UP: 3 }[o.status] || 0
  const bad = o.status === 'REJECTED' || o.status === 'EXPIRED'
  const mood = o.status === 'READY' || o.status === 'PICKED_UP' ? 'done' : bad ? 'alert' : 'clipboard'
  return (
    <div className="min-h-screen bg-paper pb-10">
      <div className="bg-gradient-to-b from-gold-50 to-paper px-5 pt-8 pb-4 flex flex-col items-center text-center">
        <StoreLogo s={s} size="w-16 h-16 text-3xl" />
        <h1 className="mt-3 font-black text-xl text-ink">{s.name}</h1>
        <p className="text-sm font-bold text-ink-mute">{AK.pickup}</p>
      </div>
      <div className="max-w-md mx-auto px-4 space-y-4">
        <div className="bg-white rounded-3xl shadow-card p-5 flex flex-col items-center text-center">
          <Beaver mood={mood} className="w-28" bob={!bad && step < 3} />
          {o.pickup_code && (
            <>
              <p className="mt-2 text-sm font-black text-ink-mute">{AK.pickupCode}</p>
              <p className="text-6xl font-black text-sky-700 tracking-tight">{o.pickup_code}</p>
            </>
          )}
          <p className={`mt-3 text-xl font-black ${bad ? 'text-red-600' : o.status === 'READY' ? 'text-emerald-600' : 'text-ink'}`}>{AK.st[o.status]}</p>
          {o.status === 'ACCEPTED' && o.ready_by && <p className="mt-1 font-bold text-gold-700 flex items-center gap-1"><Clock className="w-4 h-4" />{AK.readyBy(timeOf(o.ready_by, s.timezone))}</p>}
          {o.status === 'READY' && o.ready_at && <p className="mt-1 text-sm text-ink-mute">{AK.readyAt(timeOf(o.ready_at, s.timezone))}</p>}
          {o.status === 'REJECTED' && (
            <>
              {o.reject_reason && <p className="mt-1 text-sm text-ink-soft">{o.reject_reason}</p>}
              <p className="mt-2 text-sm font-bold text-sky-700">{o.refund ? AK.refund[o.refund] : AK.refundPending}</p>
            </>
          )}
          {step > 0 && !bad && (
            <div className="flex items-center w-full mt-5" aria-hidden="true">
              {AK.steps.map((label, i) => (
                <React.Fragment key={label}>
                  {i > 0 && <div className={`flex-1 h-1 rounded-full mx-1 ${i < step ? 'bg-sky-500' : 'bg-slate-200'}`} />}
                  <div className="flex flex-col items-center w-16">
                    <div className={`w-7 h-7 rounded-full flex items-center justify-center ${i < step ? 'bg-sky-500 text-white' : 'bg-slate-200 text-slate-400'}`}>
                      {i < step ? <Check className="w-4 h-4" /> : <span className="text-[11px] font-black">{i + 1}</span>}
                    </div>
                    <span className={`text-[11px] mt-1 font-bold ${i < step ? 'text-ink' : 'text-ink-mute'}`}>{label}</span>
                  </div>
                </React.Fragment>
              ))}
            </div>
          )}
        </div>

        <div className="bg-white rounded-3xl shadow-card p-4">
          <div className="divide-y divide-slate-100">
            {o.items.map((l, i) => (
              <div key={i} className="py-2 flex justify-between gap-3 text-sm">
                <span className="min-w-0">{l.quantity}× {l.name}{l.options?.length ? <span className="text-slate-500"> ({l.options.join(', ')})</span> : null}
                  {l.note && <span className="block text-xs text-ink-mute italic">* {l.note}</span>}</span>
                <span className="font-bold shrink-0">{money(l.unit_price_cents * l.quantity)}</span>
              </div>
            ))}
          </div>
          <div className="mt-2 pt-2 border-t border-slate-200 space-y-1 text-sm text-slate-600">
            <div className="flex justify-between"><span>{T.subtotal}</span><span className="font-bold">{money(o.subtotal_cents)}</span></div>
            <div className="flex justify-between"><span>{T.taxActual}</span><span className="font-bold">{money(o.tax_cents)}</span></div>
            {o.tip_cents > 0 && <div className="flex justify-between"><span>{T.tipTitle}</span><span className="font-bold">{money(o.tip_cents)}</span></div>}
            <div className="flex justify-between text-lg font-black text-ink pt-1"><span>{T.total}</span><span>{money(o.total_cents)}</span></div>
          </div>
          {o.note && <p className="mt-2 text-xs text-ink-mute italic">* {o.note}</p>}
        </div>

        <div className="bg-white rounded-3xl shadow-card p-4 space-y-2">
          {s.address && <p className="flex items-start gap-2 text-sm text-ink-soft"><MapPin className="w-4 h-4 mt-0.5 shrink-0 text-gold-700" /><span><span className="font-black text-ink">{AK.address}</span><br />{s.address}</span></p>}
          {s.phone && <a href={`tel:${s.phone.replace(/[^0-9+]/g, '')}`} className="flex items-center gap-2 text-sm font-black text-sky-700"><Phone className="w-4 h-4" />{AK.callStore} · {s.phone}</a>}
        </div>

        {s.slug && <a href={`?s=${s.slug}`} className="block text-center font-black text-gold-800 border-2 border-gold-500 rounded-2xl py-3.5 bg-white">{o.status === 'PICKED_UP' || bad ? AK.orderAgain : AK.backToMenu}</a>}
        <Signature product="Arke" className="pt-2" />
      </div>
    </div>
  )
}

/** The store's menu and this phone's cart. */
function StorePage({ slug }) {
  const [menu, setMenu] = useState(null)
  const [state, setState] = useState('loading')   // loading | ready | bad | offline
  const [cart, setCartState] = useState(() => store.get('arke_cart_' + slug) || [])   // [{key, product_id, option_ids, note, quantity}]
  const setCart = (c) => { setCartState(c); store.set('arke_cart_' + slug, c) }
  const [query, setQuery] = useState('')
  const [avoid, setAvoidState] = useState(() => store.get('ourea_scan_avoid') || [])
  const setAvoid = (a) => { setAvoidState(a); store.set('ourea_scan_avoid', a) }
  const [avoidOpen, setAvoidOpen] = useState(false)
  const [catsOpen, setCatsOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [dishFor, setDishFor] = useState(null)
  const [cartOpen, setCartOpen] = useState(false)
  const [checkoutOpen, setCheckoutOpen] = useState(false)
  const [confirmClear, setConfirmClear] = useState(false)
  const [toast, setToast] = useState(null)
  const flash = (msg) => { setToast(msg); setTimeout(() => setToast((t) => (t === msg ? null : t)), 3000) }

  const load = useCallback(async () => {
    const { data, error } = await supabase.rpc('online_menu', { p_slug: slug })
    if (error) { setState('offline'); return }
    if (!data) { setState('bad'); return }
    setMenu(data); setState('ready')
    document.title = `${data.store.name} · ${AK.pickup}`
  }, [slug])
  useEffect(() => { load() }, [load])

  const { categories, sections, q } = useMenuSections(menu?.products, query)
  const nav = useCategoryTabs(sections, state === 'ready')
  const byId = useMemo(() => Object.fromEntries((menu?.products || []).map((p) => [p.id, p])), [menu])

  // Cart lines with what they show; a line whose dish left the menu (or sold out) drops out.
  const lines = cart.map((l) => {
    const p = byId[l.product_id]
    const opts = (p?.modifier_groups || []).flatMap((g) => g.options).filter((o) => l.option_ids.includes(o.id))
    return { ...l, p, optionNames: opts.map((o) => o.name), unit: (p?.price_cents || 0) + opts.reduce((s, o) => s + (o.price_cents || 0), 0) }
  }).filter((l) => !menu || (l.p && !l.p.sold_out))
  const count = lines.reduce((s, l) => s + l.quantity, 0)
  const subtotal = lines.reduce((s, l) => s + l.unit * l.quantity, 0)
  // Display estimate; the server prices the order (same rule: per rate, rounded once).
  const tax = Object.entries(lines.reduce((m, l) => { const b = l.p?.tax_rate_bps ?? 0; return { ...m, [b]: (m[b] || 0) + l.unit * l.quantity } }, {}))
    .reduce((s, [bps, amt]) => s + Math.round(amt * Number(bps) / 10000), 0)
  const inCart = (pid) => lines.filter((l) => l.product_id === pid).reduce((s, l) => s + l.quantity, 0)

  const addLine = (p, { optionIds = [], note = '' } = {}, qty = 1) => {
    const same = cart.find((l) => l.product_id === p.id && (l.note || '') === note && l.option_ids.length === optionIds.length && optionIds.every((x) => l.option_ids.includes(x)))
    setCart(same
      ? cart.map((l) => (l === same ? { ...l, quantity: Math.min(20, l.quantity + qty) } : l))
      : [...cart, { key: newRef(), product_id: p.id, option_ids: optionIds, note, quantity: Math.min(qty, 20) }])
    flash(T.addedN(qty))
  }
  const setQty = (line, n) => setCart(n <= 0 ? cart.filter((l) => l.key !== line.key) : cart.map((l) => (l.key === line.key ? { ...l, quantity: Math.min(20, n) } : l)))

  if (state === 'bad') return <BeaverScreen mood="alert" text={AK.noStore} />
  if (state === 'offline') return <BeaverScreen mood="alert" text={T.offline} action={<button onClick={load} className="btn-gold mt-6 px-8 py-3.5 rounded-2xl">{T.retry}</button>} />
  if (state === 'loading' || !menu) return <BeaverScreen mood="blink" text={T.loading} />

  const s = menu.store
  const canOrder = s.open_now && s.online_pay
  return (
    <div className="min-h-screen pb-32 bg-paper">
      <header ref={nav.headerRef} className="sticky top-0 z-30 bg-white/95 backdrop-blur border-b border-slate-100">
        <div className="max-w-3xl mx-auto px-4 py-2.5 flex items-center gap-3">
          <StoreLogo s={s} size="w-10 h-10 text-lg" />
          <div className="min-w-0 flex-1">
            <h1 className="font-black text-[17px] text-ink truncate leading-tight">{s.name}</h1>
            <span className={`inline-block mt-0.5 font-black text-xs px-2 py-0.5 rounded-full ${canOrder ? 'bg-emerald-50 text-emerald-800' : 'bg-slate-100 text-ink-mute'}`}>
              {canOrder ? AK.openNow(s.pickup_minutes) : s.online_pay ? AK.closedNow : AK.payOff}
            </span>
          </div>
          <button onClick={() => setMenuOpen(true)} aria-label={T.menuTitle} className="shrink-0 w-10 h-10 rounded-full bg-slate-100 text-ink flex items-center justify-center">
            <MenuIcon className="w-5 h-5" />
          </button>
        </div>
        {s.notice && <p className="max-w-3xl mx-auto px-4 pb-2 text-sm font-bold text-gold-800">📣 {s.notice}</p>}
        <MenuFilters query={query} setQuery={setQuery} avoid={avoid} onAvoid={() => setAvoidOpen(true)}
          categories={categories} sections={sections} nav={nav} q={q} onAllCategories={() => setCatsOpen(true)} />
      </header>

      <main className="max-w-3xl mx-auto px-4 pt-2">
        {s.address && <p className="mt-3 flex items-center gap-1.5 text-sm text-ink-soft"><MapPin className="w-4 h-4 text-gold-700 shrink-0" />{AK.pickup} · {s.address}</p>}
        {sections.length === 0 && (
          <div className="flex flex-col items-center py-16 text-center">
            <Beaver mood="thinking" className="w-28" />
            <p className="mt-3 font-bold text-ink-soft">{T.noMatch}</p>
          </div>
        )}
        <MenuSections sections={sections} nav={nav} row={(p) => (
          <DishRow key={p.id} p={p} avoid={avoid} here={inCart(p.id)} onOpen={setDishFor} onQuickAdd={(x) => addLine(x)} />
        )} />
        <Signature product="Arke" className="mt-8 mb-4" />
      </main>

      {count > 0 && (
        <button onClick={() => { setConfirmClear(false); setCartOpen(true) }}
          className="fixed bottom-4 start-4 end-4 z-30 max-w-xl mx-auto bg-ink text-white ps-3 pe-5 py-3 rounded-[22px] shadow-lift flex items-center justify-between">
          <span className="flex items-center gap-3">
            <span className="bg-gradient-to-br from-gold-400 to-gold-600 text-ink font-black w-10 h-10 rounded-2xl flex items-center justify-center">{count}</span>
            <span className="font-black">{AK.cart}</span>
          </span>
          <span className="flex items-center gap-1 font-black text-lg text-gold-300">{money(subtotal + tax)}<ChevronRight className="rtl:rotate-180 w-5 h-5 text-slate-400" /></span>
        </button>
      )}

      {cartOpen && (
        <Sheet onClose={() => setCartOpen(false)} tall>
          <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between gap-2">
            <h2 className="font-black text-xl">{AK.cart}</h2>
            <div className="flex items-center gap-1 shrink-0">
              {lines.length > 0 && (confirmClear
                ? <button onClick={() => { setCart([]); setConfirmClear(false); setCartOpen(false) }} className="text-sm font-black text-red-600 px-2 py-1">{AK.confirmClear}</button>
                : <button onClick={() => setConfirmClear(true)} className="text-sm font-bold text-ink-mute px-2 py-1">{AK.clear}</button>)}
              <button onClick={() => setCartOpen(false)} aria-label={T.close} className="p-2 text-ink-mute"><X className="w-6 h-6" /></button>
            </div>
          </div>
          <div className="px-5 py-2 overflow-y-auto flex-1 divide-y divide-slate-100">
            {lines.length === 0 && <p className="py-10 text-center font-bold text-ink-mute">{AK.emptyCart}</p>}
            {lines.map((l) => (
              <div key={l.key} className="py-3 flex items-center justify-between gap-3">
                <DishImg src={l.p?.image_url} className="w-14 h-14 rounded-xl object-cover bg-slate-100 shrink-0" fallback={<div className="w-14 h-14 rounded-xl bg-gold-50 shrink-0" />} />
                <div className="min-w-0 flex-1">
                  <p className="font-black text-ink">{dishName(l.p)}</p>
                  {l.optionNames.length > 0 && <p className="text-xs text-sky-800">+ {l.optionNames.join(', ')}</p>}
                  {l.note && <p className="text-xs text-ink-mute italic">* {l.note}</p>}
                  <p className="text-xs text-ink-mute">{money(l.unit)} × {l.quantity}</p>
                </div>
                <div className="flex items-center gap-2.5 shrink-0">
                  <button onClick={() => setQty(l, l.quantity - 1)} aria-label="-" className="w-9 h-9 rounded-full bg-slate-100 flex items-center justify-center"><Minus className="w-4 h-4" /></button>
                  <span className="font-black w-4 text-center">{l.quantity}</span>
                  <button onClick={() => setQty(l, l.quantity + 1)} aria-label="+" disabled={l.quantity >= 20} className="w-9 h-9 rounded-full bg-gold-500 text-ink flex items-center justify-center"><Plus className="w-4 h-4" strokeWidth={3} /></button>
                </div>
              </div>
            ))}
          </div>
          <div className="p-5 bg-paper border-t border-slate-100 space-y-1.5">
            <div className="flex justify-between text-sm text-ink-soft"><span>{T.subtotal}</span><span className="font-bold">{money(subtotal)}</span></div>
            <div className="flex justify-between text-sm text-ink-soft"><span>{T.tax}</span><span className="font-bold">{money(tax)}</span></div>
            <div className="flex justify-between text-xl font-black pt-2 border-t border-slate-200"><span>{T.total}</span><span>{money(subtotal + tax)}</span></div>
            {!canOrder && <p className="text-sm font-bold text-red-600">{s.online_pay ? AK.closedNow : AK.payOff}</p>}
            <button onClick={() => { setCartOpen(false); setCheckoutOpen(true) }} disabled={!canOrder || count === 0}
              className="btn-gold w-full py-4 rounded-2xl text-lg mt-2">{AK.checkout}</button>
          </div>
        </Sheet>
      )}

      {checkoutOpen && (
        <Checkout slug={slug} menu={menu} lines={lines} subtotal={subtotal} tax={tax}
          onEdit={() => { setCheckoutOpen(false); setCartOpen(true) }}
          onClose={() => { setCheckoutOpen(false); load() }}
          onPaid={(token, code) => {
            setCart([])
            if (code) myOrders.add({ ...(myOrders.list().find((o) => o.token === token) || { store: s.name, at: new Date().toISOString() }), token, code })
            window.location.replace(`?o=${token}`)
          }} />
      )}

      {toast && <div className="fixed start-4 end-4 bottom-24 z-[60] max-w-md mx-auto bg-ink text-white text-sm font-bold px-4 py-3 rounded-2xl shadow-lift text-center flex items-center justify-center gap-2"><CheckCircle2 className="w-4 h-4" />{toast}</div>}
      {avoidOpen && <AvoidSheet avoid={avoid} setAvoid={setAvoid} onClose={() => setAvoidOpen(false)} />}
      {menuOpen && <MenuSheet onClose={() => setMenuOpen(false)} />}
      {catsOpen && <CategorySheet sections={sections} active={nav.activeCat} onPick={(c) => { setCatsOpen(false); nav.jumpTo(c) }} onClose={() => setCatsOpen(false)} />}
      {dishFor && (
        <DishSheet product={dishFor} avoid={avoid} onClose={() => setDishFor(null)}
          onAdd={(choice, qty) => { const p = dishFor; setDishFor(null); addLine(p, choice, qty) }} />
      )}
    </div>
  )
}

export default function ArkeApp({ slug, orderToken }) {
  if (orderToken) return <OrderPage token={orderToken} />
  return <StorePage slug={slug} />
}
