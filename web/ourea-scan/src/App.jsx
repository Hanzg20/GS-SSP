import React, { useCallback, useEffect, useMemo, useState } from 'react'
import { createClient } from '@supabase/supabase-js'
import { Plus, Minus, X, Utensils, Edit3, ChevronRight, Clock, CheckCircle2, XCircle, Loader2, Sparkles } from 'lucide-react'
import Guide from './Guide.jsx'

/*
 * Ourea Scan: a customer scans the QR on their table and orders from their
 * phone. The QR is <this site>/?t=<table secret>; the secret is all the page
 * has. Menu, prices, options and tax come from the server (scan_menu); an
 * order goes to the store as PENDING (scan_submit_order) and staff accept it
 * on Ourea / Iris before it reaches the kitchen. The page shows that status
 * (scan_orders) and never claims success it didn't get.
 * docs/database_design.md §5, docs/migrations/2026-10-13_dine_scan_orders.sql.
 */

const SUPABASE_URL = import.meta.env.VITE_SUPABASE_URL || 'https://ttbtxxpnvkcbyugzdqfw.supabase.co'
const SUPABASE_KEY = import.meta.env.VITE_SUPABASE_KEY || 'sb_publishable_g4TGLHlVn3LfafRZFOAjnA_x9M0xyBi'
const supabase = createClient(SUPABASE_URL, SUPABASE_KEY)

/** The AI guide (supabase/functions/scan-assistant); the table's QR secret is the credential. */
async function askGuide(body) {
  try {
    const res = await fetch(`${SUPABASE_URL}/functions/v1/scan-assistant`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', apikey: SUPABASE_KEY },
      body: JSON.stringify(body),
    })
    const data = await res.json().catch(() => ({}))
    return res.ok ? data : { error: data.error || 'failed' }
  } catch {
    return { error: 'offline' }
  }
}

const money = (c) => '$' + (c / 100).toFixed(2)
const zh = (navigator.language || '').toLowerCase().startsWith('zh')
const T = zh ? {
  scanFirst: '请扫描餐桌上的二维码点餐', badCode: '这个二维码已失效，请找服务员', loading: '正在加载菜单…', offline: '连不上网络，请稍后重试',
  table: '桌', all: '全部', soldOut: '已售完', add: '加入', required: '必选', pickOne: '单选', upTo: (n) => `最多选 ${n} 项`, optional: '可选',
  order: '我的点单', items: (n) => `${n} 件`, subtotal: '小计', tax: '税（估算）', total: '合计', note: '给厨房的备注', notePh: '例如：少辣、走葱',
  itemNote: '这道菜的备注', send: '提交给店员', sending: '提交中…', save: '保存', cancel: '取消',
  confirmHint: '店员确认后才会送到厨房，付款请在用餐后找服务员。', myOrders: '已提交的点单',
  pending: '等待店员确认', accepted: '已送厨房', rejected: '未被接受', orderMore: '继续点菜',
  errors: { too_many_orders: '提交太频繁了，请等店员处理后再点', sold_out: '有菜刚刚售完：', item_unavailable: '有菜已下架，请刷新菜单', bad_option: '请重新选择口味：', unknown_table: '这个二维码已失效，请找服务员' },
  failed: '没有提交成功', retry: '重试',
  contains: '含', noAllergenData: '过敏原未标注，请问服务员',
  guide: '问小海狸', guideTitle: '小海狸 · AI 点餐向导', guideHello: '你好！我是小海狸 🦫 用任何语言问我：菜是什么、辣不辣、有没有过敏原、几个人点多少。',
  guideHint: '例如：我对花生过敏，哪些能吃？', ask: '发送', listening: '正在听…点一下停止', addSuggestion: '加入',
  guideErrors: { busy: '小海狸有点忙，请过半分钟再试', table_limit: '问得有点多，请稍后再试', store_limit: '今天的提问已达上限，请问服务员', no_model: 'AI 向导暂时不可用，请问服务员', offline: '连不上网络，请稍后再试', failed: '没听明白，请换个说法再试', no_speech_service: '语音暂不可用，请打字', too_long: '录音太长了' },
  aiNote: 'AI 回答仅供参考，过敏请务必告诉服务员。',
} : {
  scanFirst: 'Scan the QR code on your table to order', badCode: 'This QR code is no longer valid. Please ask a server.', loading: 'Loading the menu…', offline: 'No connection. Please try again.',
  table: 'Table', all: 'All', soldOut: 'Sold out', add: 'Add', required: 'Required', pickOne: 'Pick one', upTo: (n) => `Up to ${n}`, optional: 'Optional',
  order: 'Your order', items: (n) => `${n} items`, subtotal: 'Subtotal', tax: 'Tax (est.)', total: 'Total', note: 'Note for the kitchen', notePh: 'e.g. less spicy, no green onion',
  itemNote: 'Note for this dish', send: 'Send to staff', sending: 'Sending…', save: 'Save', cancel: 'Cancel',
  confirmHint: 'A server confirms your order before it goes to the kitchen. Pay your server after the meal.', myOrders: 'Your orders',
  pending: 'Waiting for a server to confirm', accepted: 'Sent to the kitchen', rejected: 'Not accepted', orderMore: 'Order more',
  errors: { too_many_orders: 'Too many orders at once. Please wait for a server.', sold_out: 'Just sold out: ', item_unavailable: 'An item is no longer available. Please reload the menu.', bad_option: 'Please choose the options again: ', unknown_table: 'This QR code is no longer valid. Please ask a server.' },
  failed: 'Your order was not sent', retry: 'Try again',
  contains: 'Contains', noAllergenData: 'Allergens not listed: ask your server',
  guide: 'Ask Beaver', guideTitle: 'Beaver · AI menu guide', guideHello: "Hi! I'm Beaver 🦫 Ask me in any language: what a dish is, how spicy, allergens, how much to order for your group.",
  guideHint: "e.g. I'm allergic to peanuts, what can I eat?", ask: 'Send', listening: 'Listening… tap to stop', addSuggestion: 'Add',
  guideErrors: { busy: 'Beaver is busy, try again in half a minute', table_limit: 'Lots of questions! Please wait a moment', store_limit: "Today's questions are used up, please ask your server", no_model: 'The AI guide is unavailable, please ask your server', offline: 'No connection, try again', failed: "Sorry, I didn't get that, try rephrasing", no_speech_service: 'Voice is unavailable, please type', too_long: 'That recording is too long' },
  aiNote: 'AI answers are a guide only. Always tell your server about allergies.',
}

// Canada's priority allergens (products.attributes.allergens codes).
const ALLERGENS = zh
  ? { peanut: '花生', tree_nut: '坚果', milk: '奶', egg: '蛋', wheat: '小麦/麸质', soy: '大豆', fish: '鱼', crustacean: '甲壳类', mollusc: '贝类', sesame: '芝麻', mustard: '芥末', sulphite: '亚硫酸盐' }
  : { peanut: 'peanut', tree_nut: 'tree nuts', milk: 'milk', egg: 'egg', wheat: 'wheat/gluten', soy: 'soy', fish: 'fish', crustacean: 'shellfish', mollusc: 'molluscs', sesame: 'sesame', mustard: 'mustard', sulphite: 'sulphites' }
const DIETARY = zh
  ? { vegetarian: '🌱 素食', vegan: '🌿 纯素', halal: '☪ 清真', gluten_free: '无麸质', no_pork: '不含猪肉', no_beef: '不含牛肉' }
  : { vegetarian: '🌱 Vegetarian', vegan: '🌿 Vegan', halal: '☪ Halal', gluten_free: 'Gluten-free', no_pork: 'No pork', no_beef: 'No beef' }

/** Diet, spice and allergen badges; a dish with no allergen data says so. */
function DishFacts({ p }) {
  return (
    <div className="flex flex-wrap gap-1 mt-2">
      {(p.dietary || []).map((d) => <span key={d} className="text-[11px] font-bold bg-emerald-50 text-emerald-700 px-2 py-0.5 rounded-full">{DIETARY[d] || d}</span>)}
      {p.spice_level > 0 && <span className="text-[11px] font-bold bg-red-50 text-red-700 px-2 py-0.5 rounded-full">{'🌶'.repeat(Math.min(p.spice_level, 5))}</span>}
      {Array.isArray(p.allergens)
        ? p.allergens.length > 0 && <span className="text-[11px] font-bold bg-amber-50 text-amber-800 px-2 py-0.5 rounded-full">{T.contains}: {p.allergens.map((a) => ALLERGENS[a] || a).join(', ')}</span>
        : <span className="text-[11px] text-slate-400 px-1 py-0.5">{T.noAllergenData}</span>}
    </div>
  )
}

const store = {
  get(k) { try { return JSON.parse(localStorage.getItem(k) || 'null') } catch { return null } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)) } catch { /* private mode: fine */ } },
}

const newRef = () => (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2))

function lineKey(productId, optionIds, note) {
  return productId + '|' + [...optionIds].sort().join(',') + '|' + (note || '')
}

/** A dish's options: pick-one groups as radios, others as toggles up to max; required must be answered. */
function OptionsSheet({ product, onAdd, onClose }) {
  const groups = product.modifier_groups || []
  const [picked, setPicked] = useState({})
  const [note, setNote] = useState('')
  const toggle = (g, o) => setPicked((p) => {
    const now = p[g.id] || []
    let next
    if (now.includes(o.id)) next = g.required && g.max_select === 1 ? now : now.filter((x) => x !== o.id)
    else if (g.max_select === 1) next = [o.id]
    else if (g.max_select && now.length >= g.max_select) next = now
    else next = [...now, o.id]
    return { ...p, [g.id]: next }
  })
  const missing = groups.filter((g) => g.required && !(picked[g.id] || []).length)
  const chosen = groups.flatMap((g) => g.options.filter((o) => (picked[g.id] || []).includes(o.id)))
  const unit = product.price_cents + chosen.reduce((s, o) => s + (o.price_cents || 0), 0)
  return (
    <div className="fixed inset-0 z-50 bg-slate-900/60 flex items-end sm:items-center sm:justify-center">
      <div className="bg-white w-full max-w-lg rounded-t-3xl sm:rounded-3xl max-h-[88vh] flex flex-col">
        <div className="px-5 pt-5 pb-3 flex items-start justify-between">
          <div>
            <h2 className="font-black text-xl text-slate-900">{product.name}</h2>
            <p className="text-slate-500 font-bold">{money(product.price_cents)}</p>
            {product.description && <p className="text-sm text-slate-600 mt-1">{product.description}</p>}
            {product.guide && <p className="text-xs text-slate-500 mt-1">💡 {product.guide}</p>}
            <DishFacts p={product} />
          </div>
          <button onClick={onClose} className="p-2 text-slate-400"><X className="w-6 h-6" /></button>
        </div>
        <div className="px-5 overflow-y-auto flex-1 space-y-5 pb-4">
          {groups.map((g) => (
            <div key={g.id}>
              <div className="flex justify-between items-baseline mb-2">
                <h3 className="font-bold text-slate-900">{g.name}</h3>
                <span className={`text-xs font-bold ${missing.includes(g) ? 'text-red-600' : 'text-slate-400'}`}>
                  {g.required ? T.required : g.max_select === 1 ? T.pickOne : g.max_select ? T.upTo(g.max_select) : T.optional}
                </span>
              </div>
              <div className="flex flex-wrap gap-2">
                {g.options.map((o) => {
                  const on = (picked[g.id] || []).includes(o.id)
                  return (
                    <button key={o.id} onClick={() => toggle(g, o)}
                      className={`px-3.5 py-2 rounded-full text-sm font-bold border transition-all ${on ? 'bg-slate-900 text-white border-slate-900' : 'bg-white text-slate-700 border-slate-300'}`}>
                      {o.name}{o.price_cents ? ` +${money(o.price_cents)}` : ''}
                    </button>
                  )
                })}
              </div>
            </div>
          ))}
          <div>
            <h3 className="font-bold text-slate-900 mb-2">{T.itemNote}</h3>
            <input value={note} onChange={(e) => setNote(e.target.value.slice(0, 120))} placeholder={T.notePh}
              className="w-full px-4 py-3 rounded-xl border border-slate-300" />
          </div>
        </div>
        <div className="p-5 border-t border-slate-100">
          <button disabled={missing.length > 0}
            onClick={() => onAdd({ optionIds: chosen.map((o) => o.id), optionNames: chosen.map((o) => o.name), unit, note: note.trim() })}
            className="w-full bg-emerald-600 disabled:bg-slate-300 text-white font-black py-4 rounded-2xl text-lg">
            {T.add} · {money(unit)}
          </button>
        </div>
      </div>
    </div>
  )
}

function StatusBadge({ status, reason }) {
  if (status === 'ACCEPTED') return <span className="flex items-center gap-1 text-emerald-700 font-bold"><CheckCircle2 className="w-4 h-4" />{T.accepted}</span>
  if (status === 'REJECTED') return <span className="flex items-center gap-1 text-red-600 font-bold"><XCircle className="w-4 h-4" />{T.rejected}{reason ? ` · ${reason}` : ''}</span>
  return <span className="flex items-center gap-1 text-amber-600 font-bold"><Clock className="w-4 h-4 animate-pulse" />{T.pending}</span>
}

export default function App() {
  const token = useMemo(() => new URLSearchParams(window.location.search).get('t') || '', [])
  const [menu, setMenu] = useState(null)        // {table, store, products}
  const [state, setState] = useState(token ? 'loading' : 'no-token')  // loading | ready | bad-code | offline | no-token
  const [category, setCategory] = useState(null)
  const [cart, setCart] = useState([])          // [{key, product, optionIds, optionNames, unit, qty, note}]
  const [optionsFor, setOptionsFor] = useState(null)
  const [cartOpen, setCartOpen] = useState(false)
  const [orderNote, setOrderNote] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState(null)
  const [orderIds, setOrderIds] = useState(() => store.get('scan_orders_' + token) || [])
  const [orders, setOrders] = useState([])
  const [clientRef, setClientRef] = useState(newRef)  // kept across retries: one submit = one order
  const [guideOpen, setGuideOpen] = useState(false)

  const loadMenu = useCallback(async () => {
    setState('loading')
    const { data, error: e } = await supabase.rpc('scan_menu', { p_token: token })
    if (e) { setState('offline'); return }
    if (!data) { setState('bad-code'); return }
    setMenu(data)
    setState('ready')
    document.title = `${data.store.name} · ${T.table} ${data.table.name}`
  }, [token])

  useEffect(() => { if (token) loadMenu() }, [token, loadMenu])

  // The status of this phone's orders at this table, every few seconds.
  useEffect(() => {
    if (!token || orderIds.length === 0) return
    let stop = false
    const poll = async () => {
      const { data } = await supabase.rpc('scan_orders', { p_token: token, p_ids: orderIds })
      if (!stop && Array.isArray(data)) setOrders(data)
    }
    poll()
    const id = setInterval(poll, 5000)
    return () => { stop = true; clearInterval(id) }
  }, [token, orderIds])

  const categories = useMemo(() => menu ? [...new Set(menu.products.map((p) => p.category).filter(Boolean))] : [], [menu])
  const shown = menu ? menu.products.filter((p) => !category || p.category === category) : []

  const addLine = (product, { optionIds = [], optionNames = [], unit = product.price_cents, note = '' } = {}) => {
    const key = lineKey(product.id, optionIds, note)
    setCart((c) => c.some((l) => l.key === key)
      ? c.map((l) => l.key === key ? { ...l, qty: Math.min(l.qty + 1, 20) } : l)
      : [...c, { key, product, optionIds, optionNames, unit, qty: 1, note }])
    setError(null)
  }
  const bump = (key, d) => setCart((c) => c.map((l) => l.key === key ? { ...l, qty: l.qty + d } : l).filter((l) => l.qty > 0))
  const count = cart.reduce((s, l) => s + l.qty, 0)
  const subtotal = cart.reduce((s, l) => s + l.unit * l.qty, 0)
  // Display estimate; the server prices the order (same rule: per rate, rounded once).
  const tax = Object.entries(cart.reduce((m, l) => ({ ...m, [l.product.tax_rate_bps]: (m[l.product.tax_rate_bps] || 0) + l.unit * l.qty }), {}))
    .reduce((s, [bps, amt]) => s + Math.round(amt * Number(bps) / 10000), 0)

  const submit = async () => {
    setSubmitting(true); setError(null)
    const { data, error: e } = await supabase.rpc('scan_submit_order', {
      p_token: token,
      p_items: cart.map((l) => ({ product_id: l.product.id, quantity: l.qty, option_ids: l.optionIds, note: l.note || null })),
      p_note: orderNote.trim() || null,
      p_client_ref: clientRef,
    })
    setSubmitting(false)
    if (e) { setError(T.offline); return }
    if (!data?.success) {
      const m = data?.message
      setError((T.errors[m] || T.failed) + (data?.product && (m === 'sold_out' || m === 'bad_option') ? data.product : ''))
      if (m === 'unknown_table') setState('bad-code')
      if (m === 'item_unavailable' || m === 'sold_out') loadMenu()
      return
    }
    const ids = [data.id, ...orderIds.filter((x) => x !== data.id)].slice(0, 20)
    setOrderIds(ids); store.set('scan_orders_' + token, ids)
    setCart([]); setOrderNote(''); setCartOpen(false); setClientRef(newRef())
  }

  if (state === 'no-token' || state === 'bad-code' || state === 'offline') {
    return (
      <div className="min-h-screen flex flex-col items-center justify-center p-8 text-center bg-slate-50">
        <Utensils className="w-14 h-14 text-slate-400 mb-4" />
        <p className="text-lg font-bold text-slate-700">{state === 'no-token' ? T.scanFirst : state === 'bad-code' ? T.badCode : T.offline}</p>
        {state === 'offline' && <button onClick={loadMenu} className="mt-6 bg-slate-900 text-white font-bold px-6 py-3 rounded-full">{T.retry}</button>}
      </div>
    )
  }
  if (state === 'loading' || !menu) {
    return <div className="min-h-screen flex items-center justify-center text-slate-400 gap-2"><Loader2 className="w-5 h-5 animate-spin" />{T.loading}</div>
  }

  return (
    <div className="min-h-screen pb-28 bg-slate-50">
      <header className="sticky top-0 z-30 bg-white/95 backdrop-blur border-b border-slate-200 px-4 py-3 flex items-center justify-between">
        <div className="min-w-0">
          <h1 className="font-black text-lg text-slate-900 truncate">{menu.store.name}</h1>
          <p className="text-xs text-slate-500">{T.confirmHint}</p>
        </div>
        <div className="ml-3 shrink-0 bg-emerald-100 text-emerald-800 font-black px-3 py-1.5 rounded-full">{T.table} {menu.table.name}</div>
      </header>

      {orders.length > 0 && (
        <section className="max-w-3xl mx-auto px-4 pt-4">
          <h2 className="text-sm font-bold text-slate-500 mb-2">{T.myOrders}</h2>
          <div className="space-y-2">
            {orders.map((o) => (
              <div key={o.id} className="bg-white rounded-2xl border border-slate-200 p-3">
                <div className="flex justify-between text-sm"><StatusBadge status={o.status} reason={o.reject_reason} /><span className="font-bold">{money(o.subtotal_cents + o.tax_cents)}</span></div>
                <p className="text-xs text-slate-500 mt-1">{o.items.map((l) => `${l.quantity}× ${l.name}`).join(' · ')}</p>
              </div>
            ))}
          </div>
        </section>
      )}

      {categories.length > 1 && (
        <nav className="sticky top-[65px] z-20 bg-slate-50/95 px-4 py-2.5 overflow-x-auto flex gap-2 border-b border-slate-200">
          {[null, ...categories].map((c) => (
            <button key={c || 'all'} onClick={() => setCategory(c)}
              className={`px-4 py-2 rounded-full text-sm font-bold whitespace-nowrap ${category === c ? 'bg-slate-900 text-white' : 'bg-white text-slate-600 border border-slate-200'}`}>
              {c || T.all}
            </button>
          ))}
        </nav>
      )}

      <main className="max-w-3xl mx-auto p-4 grid grid-cols-1 sm:grid-cols-2 gap-3">
        {shown.map((p) => (
          <div key={p.id} className={`bg-white rounded-2xl p-4 border border-slate-200 flex flex-col justify-between ${p.sold_out ? 'opacity-50' : ''}`}>
            <div>
              <h3 className="font-bold text-slate-900">{p.name}</h3>
              {p.description && <p className="text-xs text-slate-500 mt-1 line-clamp-2">{p.description}</p>}
              <DishFacts p={p} />
            </div>
            <div className="flex items-center justify-between pt-3">
              <span className="font-black text-slate-900 text-lg">{money(p.price_cents)}</span>
              {p.sold_out ? <span className="text-xs font-bold text-slate-500">{T.soldOut}</span> : (
                <button onClick={() => (p.modifier_groups?.length ? setOptionsFor(p) : addLine(p))}
                  className="bg-slate-900 text-white p-2.5 rounded-xl active:scale-95" aria-label={T.add}>
                  <Plus className="w-5 h-5" />
                </button>
              )}
            </div>
          </div>
        ))}
      </main>

      {count > 0 && (
        <button onClick={() => setCartOpen(true)}
          className="fixed bottom-4 left-4 right-4 z-40 max-w-xl mx-auto bg-slate-900 text-white px-5 py-4 rounded-2xl shadow-xl flex items-center justify-between">
          <span className="flex items-center gap-3"><span className="bg-emerald-500 font-bold w-7 h-7 rounded-full flex items-center justify-center text-sm">{count}</span><span className="font-bold">{T.order}</span></span>
          <span className="flex items-center gap-1 font-black text-lg">{money(subtotal + tax)}<ChevronRight className="w-5 h-5 text-slate-400" /></span>
        </button>
      )}

      {cartOpen && (
        <div className="fixed inset-0 z-50 bg-slate-900/60 flex items-end sm:items-center sm:justify-center">
          <div className="bg-white w-full max-w-lg rounded-t-3xl sm:rounded-3xl max-h-[90vh] flex flex-col">
            <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between">
              <h2 className="font-black text-xl">{T.order} · {T.table} {menu.table.name}</h2>
              <button onClick={() => setCartOpen(false)} className="p-2 text-slate-400"><X className="w-6 h-6" /></button>
            </div>
            <div className="px-5 py-3 overflow-y-auto flex-1 divide-y divide-slate-100">
              {cart.map((l) => (
                <div key={l.key} className="py-3 flex items-center justify-between gap-3">
                  <div className="min-w-0">
                    <p className="font-bold text-slate-900">{l.product.name}</p>
                    {l.optionNames.length > 0 && <p className="text-xs text-emerald-700">+ {l.optionNames.join(', ')}</p>}
                    {l.note && <p className="text-xs text-slate-500 italic">* {l.note}</p>}
                    <p className="text-xs text-slate-500">{money(l.unit)} × {l.qty}</p>
                  </div>
                  <div className="flex items-center gap-3 shrink-0">
                    <button onClick={() => bump(l.key, -1)} className="w-8 h-8 rounded-full bg-slate-100 flex items-center justify-center"><Minus className="w-4 h-4" /></button>
                    <span className="font-bold w-4 text-center">{l.qty}</span>
                    <button onClick={() => bump(l.key, 1)} disabled={l.qty >= 20} className="w-8 h-8 rounded-full bg-slate-900 text-white flex items-center justify-center"><Plus className="w-4 h-4" /></button>
                  </div>
                </div>
              ))}
              <div className="py-3">
                <label className="text-sm font-bold text-slate-700 flex items-center gap-1 mb-2"><Edit3 className="w-4 h-4" />{T.note}</label>
                <input value={orderNote} onChange={(e) => setOrderNote(e.target.value.slice(0, 200))} placeholder={T.notePh}
                  className="w-full px-4 py-3 rounded-xl border border-slate-300" />
              </div>
            </div>
            <div className="p-5 bg-slate-50 border-t border-slate-200 space-y-2">
              <div className="flex justify-between text-sm text-slate-600"><span>{T.subtotal}</span><span className="font-bold">{money(subtotal)}</span></div>
              <div className="flex justify-between text-sm text-slate-600"><span>{T.tax}</span><span className="font-bold">{money(tax)}</span></div>
              <div className="flex justify-between text-lg font-black pt-2 border-t border-slate-200"><span>{T.total}</span><span>{money(subtotal + tax)}</span></div>
              {error && <p className="text-sm font-bold text-red-600">{error}</p>}
              <p className="text-xs text-slate-500">{T.confirmHint}</p>
              <button onClick={submit} disabled={submitting || count === 0}
                className="w-full mt-2 bg-emerald-600 disabled:opacity-50 text-white font-black py-4 rounded-2xl text-lg flex items-center justify-center gap-2">
                {submitting && <Loader2 className="w-5 h-5 animate-spin" />}{submitting ? T.sending : T.send}
              </button>
            </div>
          </div>
        </div>
      )}

      <button onClick={() => setGuideOpen(true)}
        className={`fixed right-4 z-40 bg-indigo-600 text-white px-4 py-3 rounded-full shadow-xl flex items-center gap-2 font-bold ${count > 0 ? 'bottom-24' : 'bottom-4'}`}>
        <Sparkles className="w-5 h-5" />{T.guide}
      </button>

      {guideOpen && (
        <Guide token={token} products={menu.products} T={T} askGuide={askGuide} onClose={() => setGuideOpen(false)}
          onAdd={(s) => {
            const p = menu.products.find((x) => x.id === s.product_id)
            if (!p) return
            // Options the guide couldn't settle: the customer picks them.
            if (s.needs_options || (p.modifier_groups?.length && !s.option_ids.length)) { setGuideOpen(false); setOptionsFor(p); return }
            const opts = (p.modifier_groups || []).flatMap((g) => g.options).filter((o) => s.option_ids.includes(o.id))
            const choice = { optionIds: opts.map((o) => o.id), optionNames: opts.map((o) => o.name), unit: p.price_cents + opts.reduce((a, o) => a + (o.price_cents || 0), 0) }
            for (let i = 0; i < s.quantity; i++) addLine(p, choice)
          }} />
      )}

      {optionsFor && (
        <OptionsSheet product={optionsFor} onClose={() => setOptionsFor(null)}
          onAdd={(choice) => { addLine(optionsFor, choice); setOptionsFor(null) }} />
      )}
    </div>
  )
}
