import React, { useCallback, useEffect, useMemo, useState, useRef } from 'react'
import { Plus, Minus, X, ChevronRight, ChevronDown, Loader2, Phone, MapPin, Clock, CheckCircle2, AlertTriangle, ClipboardList,
  Globe, Check, Search, ShieldAlert, ShoppingBag, ChefHat, PackageCheck, XCircle, Store, Bike, CalendarClock, RotateCcw, Ticket, Award } from 'lucide-react'
import { T, LANG, LANGS, pickLang, dishName } from './i18n.js'
import { AK } from './arke-i18n.js'
import { supabase, callFunction, money, store, newRef } from './lib.js'
import { DishImg, Sheet, DishSheet, AvoidSheet, Signature, CategorySheet, useMenuSections, useCategoryTabs, DishRow } from './shared.jsx'
import { isEmail, savedEmail, saveEmail, loadNuvei, mountNuvei, TipPicker, tipCentsOf, EmailField } from './pay.jsx'

/*
 * Arke -- online ordering for pickup (owner 2026-11-16, phase 1 of
 * docs/online_ordering_design.md) and the store's own delivery (phase 2, 2026-11-17:
 * pick delivery, an address, the server's fee for its postal code zone; the order page
 * follows it out with the driver; docs/migrations/2026-11-17_arke_delivery.sql). The store's link is <this site>/?s=<slug>;
 * the customer builds a cart on this device, places the order (online_place_order),
 * pays it on Nuvei's form (arke-pay) and follows it on its own page (?o=<token>):
 * paid -> the store accepts (ready time) -> ready -> picked up. The server prices
 * everything; "paid" only when arke-pay says Nuvei confirmed it.
 * docs/migrations/2026-11-16_arke_online_orders.sql.
 *
 * Its own storefront, not the table page (owner 2026-11-17): the store's card with
 * hours and pickup time, no Beaver. Phone: one column, category tabs, cart bar at the
 * bottom, sheets. Desktop (lg): categories + search on the left, dishes in a grid, the
 * cart always open on the right.
 *
 * Order ahead (2026-11-18, docs/migrations/2026-11-18_arke_scheduled_orders.sql): "as soon as
 * possible" while the store is open, or one of the server's 15-minute slots (menu.store.slots,
 * per pickup / delivery); while closed, booking is the only way in. Order again: a past order's
 * dishes go back into the cart, minus what is sold out or gone from the menu.
 */

const callPay = (body) => callFunction('arke-pay', body, { raw: true })
const ACTIVE = ['AWAITING_PAYMENT', 'NEW', 'ACCEPTED', 'READY']

/** The orders this device placed (status links), newest first. */
const myOrders = {
  list() { return store.get('arke_orders') || [] },
  add(o) { store.set('arke_orders', [o, ...myOrders.list().filter((x) => x.token !== o.token)].slice(0, 10)) },
}

/** Put a past order's dishes into this store's cart next time its page opens (see StorePage). */
const reorder = (slug, items) => {
  store.set('arke_reorder_' + slug, (items || []).map((l) => ({ product_id: l.product_id, option_ids: l.option_ids || [], note: l.note || '', quantity: l.quantity })))
  window.location.href = `?s=${slug}`
}

const timeOf = (iso, tz) => {
  try { return new Date(iso).toLocaleTimeString(LANG, { hour: 'numeric', minute: '2-digit', timeZone: tz || undefined }) } catch { return '' }
}

/** The calendar day of a time in the store's time zone ("2026-11-18"). */
const dayKey = (t, tz) => {
  try { return new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: tz || undefined }).format(new Date(t)) } catch { return '' }
}
/** "Today" / "Tomorrow" / "Fri, Nov 21" for a time, in the store's time zone. */
const dayLabel = (t, tz) => {
  const k = dayKey(t, tz)
  if (k === dayKey(Date.now(), tz)) return AK.today
  if (k === dayKey(Date.now() + 86400000, tz)) return AK.tomorrow
  try { return new Date(t).toLocaleDateString(LANG, { weekday: 'short', month: 'short', day: 'numeric', timeZone: tz || undefined }) } catch { return k }
}
/** "Today 6:30 PM". */
const whenOf = (t, tz) => `${dayLabel(t, tz)} ${timeOf(t, tz)}`

/** Today's ISO weekday (1 = Monday) in the store's time zone. */
const isoToday = (tz) => {
  try {
    const d = new Intl.DateTimeFormat('en-US', { weekday: 'short', timeZone: tz || undefined }).format(new Date())
    return ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'].indexOf(d) + 1 || 1
  } catch { return ((new Date().getDay() + 6) % 7) + 1 }
}
/** One day's opening ranges as words ("11:00–14:00, 17:00–21:30"); null = closed that day. */
const rangesOf = (hours, day) => {
  const r = Array.isArray(hours?.[String(day)]) ? hours[String(day)] : []
  return r.length ? r.map(([a, b]) => `${a}–${b}`).join(', ') : null
}

function StoreLogo({ s, size = 'w-12 h-12 text-xl' }) {
  const [broken, setBroken] = useState(false)
  return s.logo_url && !broken
    ? <img src={s.logo_url} alt="" onError={() => setBroken(true)} className={`${size} rounded-2xl object-cover bg-white shadow-card shrink-0`} />
    : <div className={`${size} rounded-2xl bg-gradient-to-br from-gold-400 to-gold-600 text-ink font-black flex items-center justify-center shrink-0`}>{s.name.trim().charAt(0)}</div>
}

/** Full-page message (loading, bad link, offline). */
function ArkeScreen({ busy, text, action }) {
  return (
    <div className="relative min-h-screen flex flex-col items-center justify-center p-8 text-center bg-paper">
      <div className={`w-16 h-16 rounded-2xl flex items-center justify-center ${busy ? 'bg-white shadow-card' : 'bg-gold-50'}`}>
        {busy ? <Loader2 className="w-8 h-8 text-gold-600 animate-spin" /> : <Store className="w-8 h-8 text-gold-700" />}
      </div>
      <p className="mt-5 text-lg font-black text-ink max-w-xs">{text}</p>
      {action}
      <Signature className="absolute bottom-6" product="Arke" />
    </div>
  )
}

/** Language and the orders this device placed. */
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

/** The store's card: pickup time, today's hours (tap for the week), address, phone, notice. */
/** Pickup or delivery, for a store that delivers (else just "Pickup"). */
function ModeSwitch({ s, mode, setMode }) {
  if (!s.delivery) {
    return (
      <div className="mt-3 inline-flex items-center gap-2 rounded-2xl bg-ink text-white ps-3 pe-4 py-2">
        <ShoppingBag className="w-4 h-4 text-gold-300" />
        <span className="font-black text-sm">{AK.pickup}</span>
        <span className="text-xs text-slate-300">· {AK.pickupIn(s.pickup_minutes)}</span>
      </div>
    )
  }
  const opt = (m, Icon, label, sub) => (
    <button key={m} onClick={() => setMode(m)} aria-pressed={mode === m}
      className={`flex-1 min-w-0 flex items-center gap-2 rounded-xl px-3 py-2 text-start transition-colors ${mode === m ? 'bg-ink text-white shadow-card' : 'text-ink-soft'}`}>
      <Icon className={`w-4 h-4 shrink-0 ${mode === m ? 'text-gold-300' : ''}`} />
      <span className="min-w-0">
        <span className="block font-black text-sm">{label}</span>
        <span className={`block text-[11px] truncate ${mode === m ? 'text-slate-300' : 'text-ink-mute'}`}>{sub}</span>
      </span>
    </button>
  )
  return (
    <div className="mt-3 flex gap-1 p-1 rounded-2xl bg-slate-100 max-w-md">
      {opt('PICKUP', ShoppingBag, AK.pickup, AK.pickupIn(s.pickup_minutes))}
      {opt('OWN_DELIVERY', Bike, AK.delivery, `${AK.deliveryIn(s.delivery.minutes)} · ${AK.feeFrom(money(s.delivery.fee_from_cents))}`)}
    </div>
  )
}

function StoreCard({ s, canOrder, mode, setMode }) {
  const ahead = canOrder && !s.open_now
  const [week, setWeek] = useState(false)
  const today = isoToday(s.timezone)
  const hasHours = s.hours && typeof s.hours === 'object'
  const todayRanges = hasHours ? rangesOf(s.hours, today) : null
  return (
    <div className="bg-white rounded-3xl shadow-card overflow-hidden">
      <div className="h-20 sm:h-24 bg-gradient-to-r from-gold-100 via-gold-50 to-sky-100" />
      <div className="px-4 sm:px-5 pb-4 -mt-9">
        <div className="flex items-end gap-3">
          <div className="p-1 bg-white rounded-[20px] shadow-card"><StoreLogo s={s} size="w-16 h-16 text-2xl" /></div>
          <span className={`mb-1 inline-flex items-center gap-1 font-black text-xs px-2.5 py-1 rounded-full ${ahead ? 'bg-gold-50 text-gold-800' : canOrder ? 'bg-emerald-50 text-emerald-800' : 'bg-slate-100 text-ink-mute'}`}>
            <span className={`w-1.5 h-1.5 rounded-full ${ahead ? 'bg-gold-500' : canOrder ? 'bg-emerald-500' : 'bg-slate-400'}`} />
            {ahead ? AK.orderAhead : canOrder ? AK.open : s.online_pay ? AK.closedNow : AK.payOff}
          </span>
        </div>
        <h1 className="mt-2 font-black text-2xl text-ink leading-tight">{s.name}</h1>
        <ModeSwitch s={s} mode={mode} setMode={setMode} />
        <div className="mt-3 space-y-1.5 text-sm text-ink-soft">
          {hasHours && (
            <button onClick={() => setWeek(!week)} className="flex items-center gap-2 text-start">
              <Clock className="w-4 h-4 text-gold-700 shrink-0" />
              <span>{todayRanges ? AK.todayHours(todayRanges) : AK.closedToday}</span>
              <ChevronDown className={`w-4 h-4 text-ink-mute transition-transform ${week ? 'rotate-180' : ''}`} />
            </button>
          )}
          {week && (
            <div className="ms-6 grid grid-cols-[auto_1fr] gap-x-4 gap-y-0.5 text-xs">
              {AK.days.map((d, i) => (
                <React.Fragment key={d}>
                  <span className={`font-bold ${i + 1 === today ? 'text-ink' : 'text-ink-mute'}`}>{d}</span>
                  <span className={i + 1 === today ? 'text-ink font-bold' : ''}>{rangesOf(s.hours, i + 1) || AK.closed}</span>
                </React.Fragment>
              ))}
            </div>
          )}
          {s.address && <p className="flex items-start gap-2"><MapPin className="w-4 h-4 mt-0.5 text-gold-700 shrink-0" />{s.address}</p>}
          {s.phone && <a href={`tel:${s.phone.replace(/[^0-9+]/g, '')}`} className="flex items-center gap-2 font-bold text-sky-700"><Phone className="w-4 h-4 shrink-0" />{s.phone}</a>}
        </div>
        {s.notice && <p className="mt-3 text-sm font-bold text-gold-800 bg-gold-50 rounded-xl px-3 py-2">📣 {s.notice}</p>}
      </div>
    </div>
  )
}

/** Search box + allergy filter (top of the phone's sticky bar; top of the desktop's left column). */
function SearchRow({ query, setQuery, avoid, onAvoid, stacked = false }) {
  return (
    <div className={stacked ? 'space-y-2' : 'flex gap-2'}>
      <label className="flex-1 min-w-0 flex items-center gap-2 bg-white rounded-xl px-3 py-2 border border-slate-200 focus-within:border-gold-500">
        <Search className="w-4 h-4 text-ink-mute shrink-0" />
        <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={T.search} className="bg-transparent outline-none w-full text-[15px] min-w-0" />
        {query && <button onClick={() => setQuery('')} aria-label={T.close}><X className="w-4 h-4 text-ink-mute" /></button>}
      </label>
      <button onClick={onAvoid} aria-label={T.avoid}
        className={`shrink-0 px-3 rounded-xl text-sm font-black border flex items-center gap-1 ${stacked ? 'w-full py-2' : ''} ${avoid.length ? 'bg-red-50 border-red-300 text-red-700' : 'bg-white border-slate-200 text-ink-soft'}`}>
        <ShieldAlert className="w-4 h-4" />{T.avoid}{avoid.length ? ` ${avoid.length}` : ''}
      </button>
    </div>
  )
}

/** The cart's lines with +/- and the totals; [action] is the checkout button. */
function CartBody({ lines, subtotal, tax, setQty, footer }) {
  return (
    <>
      <div className="px-5 py-1 overflow-y-auto flex-1 divide-y divide-slate-100">
        {lines.length === 0 && (
          <div className="py-10 flex flex-col items-center text-center">
            <ShoppingBag className="w-10 h-10 text-slate-300" />
            <p className="mt-2 font-bold text-ink-mute">{AK.emptyCart}</p>
          </div>
        )}
        {lines.map((l) => (
          <div key={l.key} className="py-3 flex items-center justify-between gap-3">
            <DishImg src={l.p?.image_url} className="w-12 h-12 rounded-xl object-cover bg-slate-100 shrink-0" fallback={<div className="w-12 h-12 rounded-xl bg-gold-50 shrink-0" />} />
            <div className="min-w-0 flex-1">
              <p className="font-black text-ink text-[15px] leading-snug">{dishName(l.p)}</p>
              {l.optionNames.length > 0 && <p className="text-xs text-sky-800">+ {l.optionNames.join(', ')}</p>}
              {l.note && <p className="text-xs text-ink-mute italic">* {l.note}</p>}
              <p className="text-xs text-ink-mute">{money(l.unit * l.quantity)}</p>
            </div>
            <div className="flex items-center gap-2 shrink-0">
              <button onClick={() => setQty(l, l.quantity - 1)} aria-label="-" className="w-8 h-8 rounded-full bg-slate-100 flex items-center justify-center"><Minus className="w-4 h-4" /></button>
              <span className="font-black w-4 text-center">{l.quantity}</span>
              <button onClick={() => setQty(l, l.quantity + 1)} aria-label="+" disabled={l.quantity >= 20} className="w-8 h-8 rounded-full bg-gold-500 text-ink flex items-center justify-center"><Plus className="w-4 h-4" strokeWidth={3} /></button>
            </div>
          </div>
        ))}
      </div>
      {lines.length > 0 && (
        <div className="p-5 bg-paper border-t border-slate-100 space-y-1.5">
          <div className="flex justify-between text-sm text-ink-soft"><span>{T.subtotal}</span><span className="font-bold">{money(subtotal)}</span></div>
          <div className="flex justify-between text-sm text-ink-soft"><span>{T.tax}</span><span className="font-bold">{money(tax)}</span></div>
          <div className="flex justify-between text-xl font-black pt-2 border-t border-slate-200"><span>{T.total}</span><span>{money(subtotal + tax)}</span></div>
          {footer}
        </div>
      )}
    </>
  )
}

/** Cart -> who picks it up -> tip -> email -> Nuvei's card form -> the order's page. */
/**
 * "Have a coupon?": the store's Cael coupon, checked by the server for this cart (online_coupon_check);
 * the order is placed with it and the server checks it again. [coupon] = {code, discount_cents, tax_cents}.
 */
function CouponField({ slug, items, coupon, setCoupon }) {
  const [open, setOpen] = useState(!!coupon)
  const [code, setCode] = useState(coupon?.code || '')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState(null)
  const apply = async () => {
    if (!code.trim()) return
    setBusy(true); setErr(null)
    const { data, error } = await supabase.rpc('online_coupon_check', { p_slug: slug, p_code: code.trim(), p_items: items })
    setBusy(false)
    if (error) { setErr(AK.errors.offline); return }
    if (!data?.success) { setErr(AK.errors[data?.message] || AK.errors.coupon_unknown); return }
    setCoupon({ code: data.code, discount_cents: data.discount_cents, tax_cents: data.tax_cents })
  }
  if (coupon) {
    return (
      <div className="flex items-center gap-2 rounded-xl bg-emerald-50 border border-emerald-200 px-4 py-3">
        <Ticket className="w-4 h-4 text-emerald-700 shrink-0" />
        <span className="flex-1 min-w-0 text-sm font-bold text-emerald-800">{AK.couponOn(coupon.code, money(coupon.discount_cents))}</span>
        <button onClick={() => { setCoupon(null); setCode('') }} className="text-sm font-bold text-ink-soft underline">{AK.couponRemove}</button>
      </div>
    )
  }
  if (!open) {
    return <button onClick={() => setOpen(true)} className="flex items-center gap-1.5 text-sm font-bold text-sky-700"><Ticket className="w-4 h-4" />{AK.couponAsk}</button>
  }
  return (
    <div>
      <h3 className="font-black text-ink mb-2 flex items-center gap-1.5"><Ticket className="w-4 h-4 text-gold-600" />{AK.couponTitle}</h3>
      <div className="flex gap-2">
        <input value={code} onChange={(e) => { setCode(e.target.value.slice(0, 40)); setErr(null) }} onKeyDown={(e) => e.key === 'Enter' && apply()}
          placeholder={AK.couponPh} autoCapitalize="characters" className="flex-1 min-w-0 px-4 py-3 rounded-xl border border-slate-300 uppercase" />
        <button onClick={apply} disabled={busy || !code.trim()} className="px-5 rounded-xl bg-ink text-white font-bold disabled:opacity-40">
          {busy ? <Loader2 className="w-4 h-4 animate-spin" /> : AK.couponApply}
        </button>
      </div>
      {err && <p className="mt-1.5 text-sm font-bold text-red-600">{err}</p>}
    </div>
  )
}

/**
 * The store's rewards: points on this order under the phone number, ticked unless the customer unticked it once
 * (remembered). Earned only when the order is paid (server side); spending them is at the counter.
 */
function PointsField({ rule, base, earn, setEarn }) {
  const n = Math.floor(Math.max(base, 0) / 100) * rule.per_dollar
  return (
    <label className={`flex items-start gap-3 rounded-2xl border p-3 cursor-pointer ${earn ? 'border-gold-400 bg-gold-50' : 'border-slate-200 bg-white'}`}>
      <input type="checkbox" checked={earn} onChange={(e) => setEarn(e.target.checked)} className="mt-1 h-5 w-5 accent-amber-500" />
      <Award className="w-5 h-5 mt-0.5 shrink-0 text-gold-700" />
      <span className="min-w-0">
        <span className="block font-black text-ink">{AK.pointsEarn(n)}</span>
        <span className="block text-xs text-slate-600">{AK.pointsRule(rule.reward_points, money(rule.reward_cents))}</span>
      </span>
    </label>
  )
}

function Checkout({ slug, menu, mode, lines, subtotal, tax, onEdit, onClose, onPaid }) {
  const [info, setInfo] = useState(() => store.get('arke_customer') || { name: '', phone: '' })
  const [note, setNote] = useState('')
  const [tip, setTip] = useState(null)
  const [customTip, setCustomTip] = useState(null)
  const [email, setEmail] = useState(savedEmail)
  const [state, setState] = useState('form')   // form | placing | paying | confirming | failed | review | pending
  const [error, setError] = useState(null)
  const [session, setSession] = useState(null)
  const [order, setOrder] = useState(null)      // {id, token, subtotal_cents, tax_cents} once placed
  const [coupon, setCoupon] = useState(null)    // {code, discount_cents, tax_cents} from online_coupon_check
  const [earn, setEarnState] = useState(() => store.get('arke_points') !== false)
  const setEarn = (v) => { setEarnState(v); store.set('arke_points', v) }
  const items = useMemo(() => lines.map((l) => ({ product_id: l.product_id, option_ids: l.option_ids, note: l.note || null, quantity: l.quantity })), [lines])
  useEffect(() => {
    if (!coupon) return
    supabase.rpc('online_coupon_check', { p_slug: slug, p_code: coupon.code, p_items: items }).then(({ data }) => {
      setCoupon(data?.success ? { code: data.code, discount_cents: data.discount_cents, tax_cents: data.tax_cents } : null)
    })
  }, [items]) // eslint-disable-line react-hooks/exhaustive-deps
  const clientRef = useRef(newRef())            // one checkout = one order, across retries
  const confirmed = useRef(false)
  const s = menu.store
  const delivery = mode === 'OWN_DELIVERY'
  // When: null = as soon as possible (only while open), else one of the server's slots.
  const slots = s.slots?.[mode] || []
  const [when, setWhen] = useState(() => (s.open_now ? null : slots[0] ?? null))
  useEffect(() => { if (when !== null && !slots.includes(when)) setWhen(s.open_now ? null : slots[0] ?? null) }, [slots.join()]) // eslint-disable-line react-hooks/exhaustive-deps
  // [[day, its first slot]] -- reversed so the Map keeps each day's FIRST slot (a Map keeps the last value per key).
  const days = [...new Map(slots.slice().reverse().map((t) => [dayKey(t, s.timezone), t])).entries()].reverse()
  const leadMin = delivery ? s.delivery?.minutes : s.pickup_minutes
  const [addr, setAddr] = useState(() => ({ line1: '', unit: '', city: '', postal: '', instructions: '', ...(store.get('arke_address') || {}) }))
  const postal = (addr.postal || '').toUpperCase().replace(/[^A-Z0-9]/g, '')
  const [quote, setQuote] = useState(null)      // null | {checking} | {ok, fee, feeTax, zone} | {ok: false, msg}
  // The fee for this postal code, from the server (it prices the order the same way when placing it).
  useEffect(() => {
    if (!delivery || postal.length !== 6) { setQuote(null); return }
    setQuote({ checking: true })
    let gone = false
    const t = setTimeout(async () => {
      const { data, error: e } = await supabase.rpc('online_delivery_quote', { p_slug: slug, p_postal: postal, p_subtotal_cents: subtotal })
      if (gone) return
      if (e || !data) { setQuote({ ok: false, msg: AK.quote.failed }); return }
      if (data.success) { setQuote({ ok: true, fee: data.fee_cents, feeTax: data.fee_tax_cents, zone: data.zone }); return }
      setQuote({ ok: false, msg: data.message === 'below_minimum' ? AK.quote.below_minimum(money(data.min_order_cents)) : AK.quote[data.message] || AK.quote.failed })
    }, 350)
    return () => { gone = true; clearTimeout(t) }
  }, [delivery, postal, subtotal, slug])
  // A different address is a different order: one placed for the old address is left to expire.
  useEffect(() => { if (order && state === 'form') { setOrder(null); clientRef.current = newRef() } }, [addr.line1, addr.unit, postal, when, coupon?.code, earn]) // eslint-disable-line react-hooks/exhaustive-deps
  const fee = delivery && quote?.ok ? quote.fee : 0
  const feeTax = delivery && quote?.ok ? quote.feeTax : 0
  const addrOk = !delivery || ((addr.line1 || '').trim().length >= 3 && !!quote?.ok)

  useEffect(() => { loadNuvei().catch(() => {}) }, [])   // ready by the time they tap Pay

  const tipCents = tipCentsOf(tip, customTip, subtotal)
  const phoneOk = (info.phone || '').replace(/[^0-9]/g, '').length >= 10
  const whenOk = when !== null || s.open_now
  const ready = info.name.trim() && phoneOk && addrOk && whenOk && tip !== null && isEmail(email.trim())
  // With a coupon the items' tax is the server's, worked out after the discount.
  const disc = coupon?.discount_cents || 0
  const itemTax = coupon ? coupon.tax_cents : tax
  const due = subtotal - disc + fee + itemTax + feeTax + tipCents

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
    if (delivery) store.set('arke_address', addr)
    saveEmail(email.trim())
    setState('placing')
    let o = order
    if (!o) {
      const { data, error: e } = await supabase.rpc('online_place_order', {
        p_slug: slug, p_items: items,
        p_name: info.name.trim(), p_phone: info.phone.trim(), p_note: note.trim() || null, p_client_ref: clientRef.current,
        p_fulfilment: mode, p_address: delivery ? { ...addr, postal } : null, p_scheduled_for: when, p_coupon: coupon?.code || null,
        p_points: !!(s.points && earn),
      })
      if (e) { setState('form'); setError(AK.errors.offline); return }
      if (!data?.success) {
        setState('form')
        if (data?.message?.startsWith('coupon_')) setCoupon(null)
        setError(data?.message === 'below_minimum' && data.min_order_cents != null ? AK.quote.below_minimum(money(data.min_order_cents))
          : (AK.errors[data?.message] || AK.errors.failed) + (data?.product ? data.product : ''))
        return
      }
      o = data
      setOrder(data)
      myOrders.add({ token: data.token, store: s.name, slug, at: new Date().toISOString() })
    }
    const r = await callPay({ action: 'open', token: o.token, tip_cents: tipCents, email: email.trim(), lang: LANG })
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

  const summary = (
    <div className="lg:bg-paper lg:rounded-2xl lg:p-4">
      <div className="flex items-center justify-between mb-1">
        <h3 className="font-black text-ink">{AK.cart}</h3>
        {(state === 'form' || state === 'placing') && <button onClick={onEdit} className="text-sm font-bold text-sky-700">{AK.edit}</button>}
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
        {delivery && <div className="flex justify-between"><span>{AK.deliveryFee}{quote?.ok && quote.zone ? ` · ${quote.zone}` : ''}</span><span className="font-bold">{quote?.ok ? money(fee) : '—'}</span></div>}
        {disc > 0 && <div className="flex justify-between text-emerald-700"><span>{AK.discount(coupon.code)}</span><span className="font-bold">−{money(disc)}</span></div>}
        <div className="flex justify-between"><span>{T.taxActual}</span><span className="font-bold">{money(itemTax + feeTax)}</span></div>
        {tip !== null && tipCents > 0 && <div className="flex justify-between"><span>{T.tipTitle}</span><span className="font-bold">{money(tipCents)}</span></div>}
        <div className="flex justify-between text-base font-black text-ink pt-1"><span>{T.total}</span><span>{money(due)}</span></div>
      </div>
      <p className="mt-3 flex items-center gap-1.5 text-sm font-black text-ink"><CalendarClock className="w-4 h-4 shrink-0 text-gold-700" />
        {when ? (delivery ? AK.deliverAt : AK.pickupAt)(whenOf(when, s.timezone)) : AK.asap(leadMin)}</p>
      {!delivery && <p className="mt-2 flex items-start gap-1.5 text-xs text-ink-mute"><MapPin className="w-3.5 h-3.5 mt-0.5 shrink-0 text-gold-700" /><span><b className="text-ink-soft">{s.name}</b>{s.address ? ` · ${s.address}` : ''}</span></p>}
    </div>
  )

  let body
  if (state === 'paying') {
    body = (
      <div className="py-3">
        <div className="flex justify-between font-black text-lg mb-3"><span>{T.payNow}</span><span>{money(due)}</span></div>
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
          <h3 className="font-black text-ink mb-2 flex items-center gap-1.5"><CalendarClock className="w-4 h-4 text-gold-600" />{delivery ? AK.whenDelivery : AK.whenPickup}</h3>
          <div className="flex flex-wrap gap-2">
            {s.open_now && (
              <button onClick={() => setWhen(null)} aria-pressed={when === null}
                className={`px-4 py-2.5 rounded-xl font-black text-sm border-2 ${when === null ? 'bg-ink text-white border-ink' : 'bg-white text-ink-soft border-slate-200'}`}>{AK.asap(leadMin)}</button>
            )}
            {slots.length > 0 && (
              <button onClick={() => setWhen(when ?? slots[0])} aria-pressed={when !== null}
                className={`px-4 py-2.5 rounded-xl font-black text-sm border-2 ${when !== null ? 'bg-ink text-white border-ink' : 'bg-white text-ink-soft border-slate-200'}`}>{AK.later}</button>
            )}
          </div>
          {when !== null && (
            <div className="mt-2 grid grid-cols-2 gap-2">
              <select value={dayKey(when, s.timezone)} aria-label={AK.later}
                onChange={(e) => setWhen(days.find(([d]) => d === e.target.value)?.[1] ?? when)}
                className="w-full px-3 py-3 rounded-xl border border-slate-300 bg-white font-bold">
                {days.map(([d, first]) => <option key={d} value={d}>{dayLabel(first, s.timezone)}</option>)}
              </select>
              <select value={when} onChange={(e) => setWhen(e.target.value)} aria-label={AK.later}
                className="w-full px-3 py-3 rounded-xl border border-slate-300 bg-white font-bold">
                {slots.filter((t) => dayKey(t, s.timezone) === dayKey(when, s.timezone)).map((t) => <option key={t} value={t}>{timeOf(t, s.timezone)}</option>)}
              </select>
            </div>
          )}
        </div>
        {delivery && (
          <div>
            <h3 className="font-black text-ink mb-2 flex items-center gap-1.5"><Bike className="w-4 h-4 text-gold-600" />{AK.deliverTo}</h3>
            <div className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_150px]">
              <input value={addr.line1} onChange={(e) => setAddr({ ...addr, line1: e.target.value.slice(0, 120) })} placeholder={AK.line1} autoComplete="address-line1"
                className="w-full px-4 py-3 rounded-xl border border-slate-300" />
              <input value={addr.unit} onChange={(e) => setAddr({ ...addr, unit: e.target.value.slice(0, 20) })} placeholder={AK.unit} autoComplete="address-line2"
                className="w-full px-4 py-3 rounded-xl border border-slate-300" />
              <input value={addr.city} onChange={(e) => setAddr({ ...addr, city: e.target.value.slice(0, 60) })} placeholder={AK.city} autoComplete="address-level2"
                className="w-full px-4 py-3 rounded-xl border border-slate-300" />
              <input value={addr.postal} onChange={(e) => setAddr({ ...addr, postal: e.target.value.toUpperCase().slice(0, 7) })} placeholder={AK.postal} autoComplete="postal-code"
                className="w-full px-4 py-3 rounded-xl border border-slate-300 uppercase" />
            </div>
            <input value={addr.instructions} onChange={(e) => setAddr({ ...addr, instructions: e.target.value.slice(0, 200) })} placeholder={AK.instructionsPh}
              aria-label={AK.instructions} className="mt-2 w-full px-4 py-3 rounded-xl border border-slate-300" />
            {quote?.checking && <p className="mt-1.5 text-xs text-ink-mute flex items-center gap-1"><Loader2 className="w-3 h-3 animate-spin" />{AK.checking}</p>}
            {quote?.ok && <p className="mt-1.5 text-xs font-bold text-emerald-700 flex items-center gap-1"><Check className="w-3.5 h-3.5" />{AK.zoneFee(quote.zone, money(quote.fee))}</p>}
            {quote && !quote.checking && !quote.ok && <p className="mt-1.5 text-xs font-bold text-red-600">{quote.msg}</p>}
          </div>
        )}
        <div>
          <h3 className="font-black text-ink mb-2">{delivery ? AK.recipient : AK.yourInfo}</h3>
          <div className="grid gap-2 sm:grid-cols-2">
            <input value={info.name} onChange={(e) => setInfo({ ...info, name: e.target.value.slice(0, 40) })} placeholder={AK.name} autoComplete="name"
              className="w-full px-4 py-3 rounded-xl border border-slate-300" />
            <input value={info.phone} onChange={(e) => setInfo({ ...info, phone: e.target.value.slice(0, 20) })} placeholder={AK.phone}
              type="tel" inputMode="tel" autoComplete="tel" className="w-full px-4 py-3 rounded-xl border border-slate-300" />
          </div>
          <p className="text-xs text-slate-500 mt-1.5">{AK.phoneHint}</p>
        </div>
        <div>
          <h3 className="font-black text-ink mb-2">{AK.orderNote}</h3>
          <input value={note} onChange={(e) => setNote(e.target.value.slice(0, 200))} placeholder={AK.orderNotePh}
            className="w-full px-4 py-3 rounded-xl border border-slate-300" />
        </div>
        <TipPicker T={T} basis={subtotal} tip={tip} setTip={setTip} customTip={customTip} setCustomTip={setCustomTip} />
        <CouponField slug={slug} items={items} coupon={coupon} setCoupon={setCoupon} />
        {s.points && <PointsField rule={s.points} base={subtotal - disc} earn={earn} setEarn={setEarn} />}
        <EmailField T={T} email={email} setEmail={setEmail} />
      </div>
    )
  }

  const button = (state === 'form' || state === 'placing') && (
    <div>
      {error && <p className="text-sm font-bold text-red-600 mb-2">{error}</p>}
      <button onClick={pay} disabled={!ready || state === 'placing'} className="btn-gold w-full py-4 rounded-2xl text-lg flex items-center justify-center gap-2">
        {state === 'placing' && <Loader2 className="w-5 h-5 animate-spin" />}
        {state === 'placing' ? AK.placing
          : delivery && !addrOk ? (quote && !quote.checking && !quote.ok ? quote.msg : AK.fillAddress)
          : !info.name.trim() || !phoneOk ? AK.fillInfo
          : tip === null ? T.chooseTip
          : !isEmail(email.trim()) ? T.enterEmail
          : AK.payAndOrder(money(due))}
      </button>
    </div>
  )

  // Phone: a sheet from the bottom, the order summary first. Desktop: a wide dialog, the form
  // on the left, the summary and the pay button on the right.
  return (
    <div className="fixed inset-0 z-50 bg-ink/60 flex items-end sm:items-center sm:justify-center sm:p-6">
      <div className="bg-white w-full max-w-lg lg:max-w-4xl rounded-t-3xl sm:rounded-3xl max-h-[92vh] flex flex-col">
        <div className="px-5 lg:px-7 py-4 border-b border-slate-100 flex items-center justify-between">
          <h2 className="font-black text-xl flex items-center gap-2">{delivery ? <Bike className="w-5 h-5 text-gold-600" /> : <ShoppingBag className="w-5 h-5 text-gold-600" />}{AK.checkout} · {delivery ? AK.delivery : AK.pickup}</h2>
          <button onClick={close} aria-label={T.close} className="p-2 text-slate-400"><X className="w-6 h-6" /></button>
        </div>
        <div className="overflow-y-auto flex-1 lg:grid lg:grid-cols-[minmax(0,1fr)_340px] lg:gap-7 px-5 lg:px-7 py-3 lg:py-5">
          <div className="lg:order-2 space-y-4 mb-4 lg:mb-0">
            {summary}
            <div className="hidden lg:block">{button}</div>
          </div>
          <div className="lg:order-1 min-w-0">{body}</div>
        </div>
        {button && <div className="lg:hidden p-5 bg-slate-50 border-t border-slate-200">{button}</div>}
      </div>
    </div>
  )
}

const STATUS_LOOK = {
  AWAITING_PAYMENT: { Icon: Clock, ring: 'bg-slate-100 text-ink-mute' },
  NEW: { Icon: ClipboardList, ring: 'bg-sky-50 text-sky-700' },
  ACCEPTED: { Icon: ChefHat, ring: 'bg-gold-50 text-gold-700' },
  READY: { Icon: PackageCheck, ring: 'bg-emerald-50 text-emerald-600' },
  OUT_FOR_DELIVERY: { Icon: Bike, ring: 'bg-sky-50 text-sky-700' },
  DELIVERED: { Icon: CheckCircle2, ring: 'bg-emerald-50 text-emerald-600' },
  PICKED_UP: { Icon: CheckCircle2, ring: 'bg-emerald-50 text-emerald-600' },
  REJECTED: { Icon: XCircle, ring: 'bg-red-50 text-red-600' },
  EXPIRED: { Icon: XCircle, ring: 'bg-slate-100 text-ink-mute' },
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
    ? <ArkeScreen text={T.offline} action={<button onClick={read} className="btn-gold mt-6 px-8 py-3.5 rounded-2xl">{T.retry}</button>} />
    : <ArkeScreen busy text={T.loading} />
  if (o === null) return <ArkeScreen text={AK.noStore} />

  const s = o.store
  const delivery = o.fulfilment === 'OWN_DELIVERY'
  const steps = delivery ? AK.deliverySteps : AK.steps
  const step = (delivery ? { NEW: 1, ACCEPTED: 2, READY: 2, OUT_FOR_DELIVERY: 3, DELIVERED: 4 } : { NEW: 1, ACCEPTED: 2, READY: 3, PICKED_UP: 3 })[o.status] || 0
  const bad = o.status === 'REJECTED' || o.status === 'EXPIRED'
  const good = delivery ? o.status === 'DELIVERED' : o.status === 'READY'
  const look = delivery && o.status === 'READY' ? STATUS_LOOK.ACCEPTED : STATUS_LOOK[o.status] || STATUS_LOOK.NEW
  const { Icon, ring } = look
  const stText = delivery && o.status === 'READY' ? AK.st.ACCEPTED : AK.st[o.status]
  const a = o.delivery_address
  const sched = o.scheduled_for
  const late = sched && o.ready_by && new Date(o.ready_by) - new Date(sched) > 60000   // the store needs longer than booked
  const showSched = sched && !late && ['AWAITING_PAYMENT', 'NEW', 'ACCEPTED'].includes(o.status)
  const at = (t) => (sched ? whenOf(t, s.timezone) : timeOf(t, s.timezone))
  const finished = o.status === 'PICKED_UP' || o.status === 'DELIVERED' || bad
  return (
    <div className="min-h-screen bg-paper pb-10">
      <header className="bg-white border-b border-slate-100">
        <div className="max-w-5xl mx-auto px-4 py-3 flex items-center gap-3">
          <StoreLogo s={s} size="w-10 h-10 text-lg" />
          <div className="min-w-0 flex-1">
            <p className="font-black text-ink truncate leading-tight">{s.name}</p>
            <p className="text-xs font-bold text-ink-mute">{delivery ? AK.delivery : AK.pickup}</p>
          </div>
          {s.slug && <a href={`?s=${s.slug}`} className="hidden sm:inline-flex text-sm font-black text-gold-800 border border-gold-500 rounded-xl px-3 py-2">{AK.backToMenu}</a>}
        </div>
      </header>
      <div className="max-w-md lg:max-w-5xl mx-auto px-4 pt-4 lg:pt-8 grid gap-4 lg:grid-cols-[minmax(0,1fr)_380px] lg:gap-6 lg:items-start">
        <div className="bg-white rounded-3xl shadow-card p-6 lg:p-10 flex flex-col items-center text-center">
          <div className={`w-20 h-20 rounded-full flex items-center justify-center ${ring}`}><Icon className="w-10 h-10" /></div>
          {o.pickup_code && (
            <>
              <p className="mt-4 text-sm font-black text-ink-mute">{delivery ? AK.orderNo : AK.pickupCode}</p>
              <p className="text-6xl lg:text-7xl font-black text-ink tracking-tight">{o.pickup_code}</p>
            </>
          )}
          <p className={`mt-3 text-xl lg:text-2xl font-black ${bad ? 'text-red-600' : good ? 'text-emerald-600' : 'text-ink'}`}>{stText}</p>
          {showSched && <p className="mt-2 inline-flex items-center gap-1.5 font-black text-gold-800 bg-gold-50 rounded-full px-3 py-1"><CalendarClock className="w-4 h-4" />{(delivery ? AK.deliverAt : AK.pickupAt)(whenOf(sched, s.timezone))}</p>}
          {!delivery && o.status === 'ACCEPTED' && o.ready_by && (!sched || late) && <p className="mt-1 font-bold text-gold-700 flex items-center gap-1"><Clock className="w-4 h-4" />{AK.readyBy(at(o.ready_by))}</p>}
          {!delivery && o.status === 'READY' && o.ready_at && <p className="mt-1 text-sm text-ink-mute">{AK.readyAt(timeOf(o.ready_at, s.timezone))}</p>}
          {delivery && ['ACCEPTED', 'READY', 'OUT_FOR_DELIVERY'].includes(o.status) && o.ready_by && !showSched && <p className="mt-1 font-bold text-gold-700 flex items-center gap-1"><Clock className="w-4 h-4" />{AK.arriveBy(at(o.ready_by))}</p>}
          {o.status === 'OUT_FOR_DELIVERY' && <p className="mt-1 text-sm font-bold text-sky-700">{AK.driverOnWay}</p>}
          {o.status === 'DELIVERED' && o.delivered_at && <p className="mt-1 text-sm text-ink-mute">{AK.deliveredAt(timeOf(o.delivered_at, s.timezone))}</p>}
          {o.status === 'REJECTED' && (
            <>
              {o.reject_reason && <p className="mt-1 text-sm text-ink-soft">{o.reject_reason}</p>}
              <p className="mt-2 text-sm font-bold text-sky-700">{o.refund ? AK.refund[o.refund] : AK.refundPending}</p>
            </>
          )}
          {step > 0 && !bad && (
            <div className="flex items-center w-full max-w-sm mt-6" aria-hidden="true">
              {steps.map((label, i) => (
                <React.Fragment key={label}>
                  {i > 0 && <div className={`flex-1 h-1 rounded-full mx-1 ${i < step ? 'bg-gold-500' : 'bg-slate-200'}`} />}
                  <div className="flex flex-col items-center w-16">
                    <div className={`w-7 h-7 rounded-full flex items-center justify-center ${i < step ? 'bg-gold-500 text-ink' : 'bg-slate-200 text-slate-400'}`}>
                      {i < step ? <Check className="w-4 h-4" strokeWidth={3} /> : <span className="text-[11px] font-black">{i + 1}</span>}
                    </div>
                    <span className={`text-[11px] mt-1 font-bold ${i < step ? 'text-ink' : 'text-ink-mute'}`}>{label}</span>
                  </div>
                </React.Fragment>
              ))}
            </div>
          )}
        </div>

        <div className="space-y-4">
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
              {o.delivery_fee_cents > 0 && <div className="flex justify-between"><span>{AK.deliveryFee}</span><span className="font-bold">{money(o.delivery_fee_cents)}</span></div>}
              {o.discount_cents > 0 && <div className="flex justify-between text-emerald-700"><span>{AK.discount(o.coupon_code || '')}</span><span className="font-bold">−{money(o.discount_cents)}</span></div>}
              <div className="flex justify-between"><span>{T.taxActual}</span><span className="font-bold">{money(o.tax_cents)}</span></div>
              {o.tip_cents > 0 && <div className="flex justify-between"><span>{T.tipTitle}</span><span className="font-bold">{money(o.tip_cents)}</span></div>}
              <div className="flex justify-between text-lg font-black text-ink pt-1"><span>{T.total}</span><span>{money(o.total_cents)}</span></div>
            </div>
            {o.note && <p className="mt-2 text-xs text-ink-mute italic">* {o.note}</p>}
          </div>

          {o.points_earned != null && (
            <div className="bg-gold-50 border border-gold-300 rounded-3xl p-4 flex items-start gap-3">
              <Award className="w-6 h-6 shrink-0 text-gold-700" />
              <div className="min-w-0">
                <p className="font-black text-ink">{AK.pointsGot(o.points_earned, o.points_balance ?? o.points_earned)}</p>
                <p className="text-xs text-slate-600 mt-0.5">{AK.pointsUse}</p>
              </div>
            </div>
          )}

          <div className="bg-white rounded-3xl shadow-card p-4 space-y-2">
            {delivery && a && (
              <p className="flex items-start gap-2 text-sm text-ink-soft"><Bike className="w-4 h-4 mt-0.5 shrink-0 text-gold-700" />
                <span><span className="font-black text-ink">{AK.deliverAddress}</span><br />{a.line1}{a.unit ? `, ${a.unit}` : ''}<br />{[a.city, a.postal].filter(Boolean).join(' ')}
                  {a.instructions && <span className="block text-xs text-ink-mute italic mt-0.5">* {a.instructions}</span>}</span></p>
            )}
            {!delivery && s.address && <p className="flex items-start gap-2 text-sm text-ink-soft"><MapPin className="w-4 h-4 mt-0.5 shrink-0 text-gold-700" /><span><span className="font-black text-ink">{AK.address}</span><br />{s.address}</span></p>}
            {s.phone && <a href={`tel:${s.phone.replace(/[^0-9+]/g, '')}`} className="flex items-center gap-2 text-sm font-black text-sky-700"><Phone className="w-4 h-4" />{AK.callStore} · {s.phone}</a>}
          </div>

          {s.slug && finished && o.items?.length > 0 && (
            <button onClick={() => reorder(s.slug, o.items)} className="w-full flex items-center justify-center gap-2 btn-gold rounded-2xl py-3.5">
              <RotateCcw className="w-5 h-5" />{AK.orderAgain}</button>
          )}
          {s.slug && <a href={`?s=${s.slug}`} className="block text-center font-black text-gold-800 border-2 border-gold-500 rounded-2xl py-3.5 bg-white">{AK.backToMenu}</a>}
        </div>
      </div>
      <Signature product="Arke" className="pt-8" />
    </div>
  )
}

/** The store's menu and this device's cart. */
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
  const [mode, setModeState] = useState(() => store.get('arke_mode_' + slug) || 'PICKUP')   // PICKUP | OWN_DELIVERY
  const setMode = (m) => { setModeState(m); store.set('arke_mode_' + slug, m) }
  const [toast, setToast] = useState(null)
  const flash = (msg) => { setToast(msg); setTimeout(() => setToast((t) => (t === msg ? null : t)), 3000) }

  const load = useCallback(async () => {
    const { data, error } = await supabase.rpc('online_menu', { p_slug: slug })
    if (error) { setState('offline'); return }
    if (!data) { setState('bad'); return }
    setMenu(data); setState('ready')
    document.title = data.store.name
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

  // Order again: a past order's dishes into the cart, skipping what's sold out, gone or has options that changed.
  const reorderInto = (items) => {
    let next = [...cart], added = 0, skipped = 0
    for (const it of items || []) {
      const p = byId[it.product_id]
      const optIds = it.option_ids || []
      const known = new Set((p?.modifier_groups || []).flatMap((g) => g.options.map((o) => o.id)))
      if (!p || p.sold_out || !optIds.every((x) => known.has(x))) { skipped++; continue }
      const note = it.note || ''
      const same = next.find((l) => l.product_id === p.id && (l.note || '') === note && l.option_ids.length === optIds.length && optIds.every((x) => l.option_ids.includes(x)))
      next = same ? next.map((l) => (l === same ? { ...l, quantity: Math.min(20, l.quantity + it.quantity) } : l))
        : [...next, { key: newRef(), product_id: p.id, option_ids: optIds, note, quantity: Math.min(20, it.quantity) }]
      added += it.quantity
    }
    setCart(next)
    flash(added === 0 ? AK.reorderNone : skipped ? AK.reorderSome(added, skipped) : AK.reorderAdded(added))
  }
  useEffect(() => {   // sent here by "Order again" on an order's page
    if (!menu) return
    const pending = store.get('arke_reorder_' + slug)
    if (pending) { store.set('arke_reorder_' + slug, null); reorderInto(pending) }
  }, [menu]) // eslint-disable-line react-hooks/exhaustive-deps
  const [lastBusy, setLastBusy] = useState(false)
  const last = myOrders.list().find((o) => o.code && (o.slug ? o.slug === slug : o.store === menu?.store?.name))
  const againLast = async () => {
    setLastBusy(true)
    const { data } = await supabase.rpc('online_order_status', { p_token: last.token })
    setLastBusy(false)
    if (data?.items) reorderInto(data.items); else flash(AK.errors.offline)
  }

  const addLine = (p, { optionIds = [], note = '' } = {}, qty = 1) => {
    const same = cart.find((l) => l.product_id === p.id && (l.note || '') === note && l.option_ids.length === optionIds.length && optionIds.every((x) => l.option_ids.includes(x)))
    setCart(same
      ? cart.map((l) => (l === same ? { ...l, quantity: Math.min(20, l.quantity + qty) } : l))
      : [...cart, { key: newRef(), product_id: p.id, option_ids: optionIds, note, quantity: Math.min(qty, 20) }])
    flash(T.addedN(qty))
  }
  const setQty = (line, n) => setCart(n <= 0 ? cart.filter((l) => l.key !== line.key) : cart.map((l) => (l.key === line.key ? { ...l, quantity: Math.min(20, n) } : l)))

  if (state === 'bad') return <ArkeScreen text={AK.noStore} />
  if (state === 'offline') return <ArkeScreen text={T.offline} action={<button onClick={load} className="btn-gold mt-6 px-8 py-3.5 rounded-2xl">{T.retry}</button>} />
  if (state === 'loading' || !menu) return <ArkeScreen busy text={T.loading} />

  const s = menu.store
  const fulfil = s.delivery ? mode : 'PICKUP'   // a store that stopped delivering: back to pickup
  const slots = s.slots?.[fulfil] || []
  const canOrder = s.online_pay && (s.open_now || slots.length > 0)   // closed: ordering ahead only
  const goCheckout = () => { setCartOpen(false); setCheckoutOpen(true) }
  const checkoutButton = (
    <>
      {!canOrder && <p className="text-sm font-bold text-red-600">{s.online_pay ? AK.closedNow : AK.payOff}</p>}
      {canOrder && !s.open_now && <p className="text-sm font-bold text-gold-800">{AK.closedAhead(whenOf(slots[0], s.timezone))}</p>}
      <button onClick={goCheckout} disabled={!canOrder || count === 0} className="btn-gold w-full py-4 rounded-2xl text-lg mt-2">{AK.checkout}</button>
    </>
  )
  const clearButton = lines.length > 0 && (confirmClear
    ? <button onClick={() => { setCart([]); setConfirmClear(false); setCartOpen(false) }} className="text-sm font-black text-red-600 px-2 py-1">{AK.confirmClear}</button>
    : <button onClick={() => setConfirmClear(true)} className="text-sm font-bold text-ink-mute px-2 py-1">{AK.clear}</button>)

  return (
    <div className="min-h-screen bg-paper">
      {/* Top bar. Phone: the search and category tabs ride along under it. */}
      <header ref={nav.headerRef} className="sticky top-0 z-30 bg-white/95 backdrop-blur border-b border-slate-100">
        <div className="max-w-7xl mx-auto px-4 h-14 lg:h-16 flex items-center gap-3">
          <StoreLogo s={s} size="w-9 h-9 text-base" />
          <p className="min-w-0 flex-1 font-black text-ink truncate">{s.name}</p>
          <span className="hidden lg:inline-flex items-center gap-1.5 text-sm font-bold text-ink-soft">{fulfil === 'OWN_DELIVERY' ? <Bike className="w-4 h-4 text-gold-600" /> : <ShoppingBag className="w-4 h-4 text-gold-600" />}{fulfil === 'OWN_DELIVERY' ? AK.delivery : AK.pickup}</span>
          <button onClick={() => setMenuOpen(true)} aria-label={T.menuTitle}
            className="shrink-0 h-9 px-2.5 lg:px-3 rounded-xl bg-slate-100 text-ink flex items-center gap-1.5 text-sm font-bold">
            <Globe className="w-4 h-4" /><span className="hidden sm:inline">{LANGS.find(([l]) => l === LANG)?.[1]}</span>
            {myOrders.list().length > 0 && <ClipboardList className="w-4 h-4 ms-1" />}
          </button>
        </div>
        <div className="lg:hidden px-4 pb-2"><SearchRow query={query} setQuery={setQuery} avoid={avoid} onAvoid={() => setAvoidOpen(true)} /></div>
        {categories.length > 1 && !q && (
          <div className="lg:hidden flex items-center border-t border-slate-100">
            <nav ref={nav.tabsRef} className="flex-1 overflow-x-auto no-scrollbar flex gap-5 px-4">
              {sections.map(({ c }) => (
                <button key={c} data-cat={c} onClick={() => nav.jumpTo(c)}
                  className={`py-2.5 text-sm font-black whitespace-nowrap border-b-2 transition-colors ${nav.activeCat === c ? 'border-gold-500 text-ink' : 'border-transparent text-ink-mute'}`}>
                  {c}
                </button>
              ))}
            </nav>
            <button onClick={() => setCatsOpen(true)} aria-label={T.allCategories} className="shrink-0 px-3 py-2 text-ink-soft border-s border-slate-100"><ChevronDown className="w-5 h-5" /></button>
          </div>
        )}
      </header>

      <div className="max-w-7xl mx-auto px-4 pt-4 pb-32 lg:pb-12 lg:grid lg:grid-cols-[220px_minmax(0,1fr)_360px] lg:gap-6 lg:items-start">
        {/* Desktop, left: search, allergy filter, every category. */}
        <aside className="hidden lg:block sticky top-20 max-h-[calc(100vh-6rem)] overflow-y-auto no-scrollbar space-y-3">
          <SearchRow stacked query={query} setQuery={setQuery} avoid={avoid} onAvoid={() => setAvoidOpen(true)} />
          {sections.length > 0 && (
            <nav className="bg-white rounded-2xl shadow-card p-2">
              <p className="px-3 pt-1 pb-2 text-xs font-black text-ink-mute uppercase tracking-wide">{AK.categories}</p>
              {sections.map(({ c, items }) => (
                <button key={c} onClick={() => nav.jumpTo(c)}
                  className={`w-full flex items-center justify-between gap-2 text-start px-3 py-2 rounded-xl text-sm font-bold transition-colors ${nav.activeCat === c ? 'bg-gold-50 text-ink' : 'text-ink-soft hover:bg-slate-50'}`}>
                  <span className="truncate">{c}</span><span className="text-xs text-ink-mute">{items.length}</span>
                </button>
              ))}
            </nav>
          )}
        </aside>

        <main className="min-w-0">
          <StoreCard s={s} canOrder={canOrder} mode={fulfil} setMode={setMode} />
          {last && (
            <div className="mt-3 bg-white rounded-2xl shadow-card px-4 py-3 flex items-center gap-3">
              <RotateCcw className="w-5 h-5 text-gold-700 shrink-0" />
              <a href={`?o=${last.token}`} className="min-w-0 flex-1">
                <span className="block text-xs font-bold text-ink-mute">{AK.lastOrder}</span>
                <span className="block font-black text-ink truncate">{last.code} · {new Date(last.at).toLocaleDateString(LANG, { month: 'short', day: 'numeric' })}</span>
              </a>
              <button onClick={againLast} disabled={lastBusy} className="shrink-0 btn-gold px-4 py-2 rounded-xl text-sm flex items-center gap-1.5">
                {lastBusy && <Loader2 className="w-4 h-4 animate-spin" />}{AK.orderAgain}</button>
            </div>
          )}
          {sections.length === 0 && (
            <div className="flex flex-col items-center py-16 text-center">
              <Search className="w-10 h-10 text-slate-300" />
              <p className="mt-3 font-bold text-ink-soft">{T.noMatch}</p>
            </div>
          )}
          {sections.map(({ c, items }) => (
            <section key={c} ref={(el) => { nav.sectionRefs.current[c] = el }} className="pt-6">
              <h2 className="font-black text-xl text-ink mb-3">{c}</h2>
              <div className="grid gap-3 xl:grid-cols-2">
                {items.map((p) => (
                  <div key={p.id} className="bg-white rounded-2xl shadow-card overflow-hidden lg:hover:shadow-lift lg:cursor-pointer transition-shadow">
                    <DishRow p={p} avoid={avoid} here={inCart(p.id)} onOpen={setDishFor} onQuickAdd={(x) => addLine(x)} />
                  </div>
                ))}
              </div>
            </section>
          ))}
          <Signature product="Arke" className="mt-10 mb-4" />
        </main>

        {/* Desktop, right: the cart, always open. */}
        <aside className="hidden lg:flex sticky top-20 max-h-[calc(100vh-6rem)] flex-col bg-white rounded-3xl shadow-card overflow-hidden">
          <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between gap-2">
            <h2 className="font-black text-lg flex items-center gap-2"><ShoppingBag className="w-5 h-5 text-gold-600" />{AK.cart}{count > 0 && <span className="text-sm text-ink-mute">({count})</span>}</h2>
            {clearButton}
          </div>
          <CartBody lines={lines} subtotal={subtotal} tax={tax} setQty={setQty} footer={checkoutButton} />
        </aside>
      </div>

      {/* Phone: the cart bar at the bottom, the cart in a sheet. */}
      {count > 0 && (
        <button onClick={() => { setConfirmClear(false); setCartOpen(true) }}
          className="lg:hidden fixed bottom-4 start-4 end-4 z-30 max-w-xl mx-auto btn-gold ps-3 pe-5 py-3 rounded-2xl flex items-center justify-between">
          <span className="flex items-center gap-3">
            <span className="relative bg-ink text-gold-300 w-10 h-10 rounded-xl flex items-center justify-center">
              <ShoppingBag className="w-5 h-5" />
              <span className="absolute -top-1.5 -end-1.5 bg-white text-ink text-[11px] font-black min-w-[20px] h-5 px-1 rounded-full flex items-center justify-center shadow">{count}</span>
            </span>
            <span className="font-black">{AK.checkout}</span>
          </span>
          <span className="flex items-center gap-1 font-black text-lg">{money(subtotal + tax)}<ChevronRight className="rtl:rotate-180 w-5 h-5" /></span>
        </button>
      )}

      {cartOpen && (
        <Sheet onClose={() => setCartOpen(false)} tall>
          <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between gap-2">
            <h2 className="font-black text-xl">{AK.cart}</h2>
            <div className="flex items-center gap-1 shrink-0">
              {clearButton}
              <button onClick={() => setCartOpen(false)} aria-label={T.close} className="p-2 text-ink-mute"><X className="w-6 h-6" /></button>
            </div>
          </div>
          <CartBody lines={lines} subtotal={subtotal} tax={tax} setQty={setQty} footer={checkoutButton} />
        </Sheet>
      )}

      {checkoutOpen && (
        <Checkout slug={slug} menu={menu} mode={fulfil} lines={lines} subtotal={subtotal} tax={tax}
          onEdit={() => { setCheckoutOpen(false); if (window.matchMedia('(max-width: 1023px)').matches) setCartOpen(true) }}
          onClose={() => { setCheckoutOpen(false); load() }}
          onPaid={(token, code) => {
            setCart([])
            if (code) myOrders.add({ ...(myOrders.list().find((o) => o.token === token) || { store: s.name, at: new Date().toISOString() }), slug, token, code })
            window.location.replace(`?o=${token}`)
          }} />
      )}

      {toast && <div className="fixed start-4 end-4 bottom-24 lg:bottom-8 z-[60] max-w-md mx-auto bg-ink text-white text-sm font-bold px-4 py-3 rounded-2xl shadow-lift text-center flex items-center justify-center gap-2"><CheckCircle2 className="w-4 h-4" />{toast}</div>}
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
