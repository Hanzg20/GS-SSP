import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createClient } from '@supabase/supabase-js'
import { Plus, Minus, X, Edit3, ChevronRight, ChevronDown, XCircle, Loader2, Bell, Search, ShieldAlert, Check, ClipboardList, Menu as MenuIcon, Globe } from 'lucide-react'
import Guide from './Guide.jsx'
import Bill from './Bill.jsx'

/*
 * Ourea Scan: a customer scans the QR on their table and orders from their
 * phone. The QR is <this site>/?t=<table secret>; the secret is all the page
 * has. Menu, prices, options and tax come from the server (scan_menu); an
 * order goes to the store as PENDING (scan_submit_order) and staff accept it
 * on Ourea / Iris before it reaches the kitchen. The page shows that status
 * (scan_orders) and never claims success it didn't get. After the meal the
 * customer can pay the table's bill here (Bill.jsx, scan-pay) when the store
 * takes online payment.
 * docs/database_design.md §5, docs/migrations/2026-10-13_dine_scan_orders.sql, 2026-10-28_scan_pay.sql.
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

/** Pay at the table (supabase/functions/scan-pay); the table's QR secret is the credential. */
async function callPay(body) {
  try {
    const res = await fetch(`${SUPABASE_URL}/functions/v1/scan-pay`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', apikey: SUPABASE_KEY },
      body: JSON.stringify(body),
    })
    return await res.json().catch(() => ({ error: 'failed' }))
  } catch {
    return { error: 'offline' }
  }
}

const money = (c) => '$' + (c / 100).toFixed(2)
// The customer's language: their own pick (header switch) wins, else the phone's.
const LANG_KEY = 'ourea_scan_lang'
const pickedLang = (() => { try { return localStorage.getItem(LANG_KEY) } catch { return null } })()
const zh = pickedLang ? pickedLang === 'zh' : (navigator.language || '').toLowerCase().startsWith('zh')
export const LANG = zh ? 'zh' : 'en'
const T = zh ? {
  scanFirst: '请扫描餐桌上的二维码点餐', badCode: '这个二维码已失效，请找服务员', loading: '正在加载菜单…', offline: '连不上网络，请稍后重试',
  table: '桌', all: '全部', soldOut: '已售完', add: '加入', required: '必选', pickOne: '单选', upTo: (n) => `最多选 ${n} 项`, optional: '可选',
  order: '我的点单', items: (n) => `${n} 件`, subtotal: '小计', tax: '税（估算）', total: '合计', note: '给厨房的备注', notePh: '例如：少辣、走葱',
  itemNote: '这道菜的备注', send: '提交给店员', sending: '提交中…', save: '保存', cancel: '取消',
  confirmHint: '店员确认后才会送到厨房。吃完点「买单」看账单。', myOrders: '已提交的点单',
  pending: '等待店员确认', accepted: '已送厨房', rejected: '未被接受', orderMore: '继续点菜',
  errors: { too_many_orders: '提交太频繁了，请等店员处理后再点', sold_out: '有菜刚刚售完：', item_unavailable: '有菜已下架，请刷新菜单', bad_option: '请重新选择口味：', unknown_table: '这个二维码已失效，请找服务员' },
  failed: '没有提交成功', retry: '重试',
  contains: '含', noAllergenData: '过敏原未标注，请问服务员',
  guide: '问小海狸', guideTitle: '小海狸 · AI 点餐向导', guideHello: '你好！我是小海狸 🦫 用任何语言问我：菜是什么、辣不辣、有没有过敏原、几个人点多少。',
  guideHint: '例如：我对花生过敏，哪些能吃？', ask: '发送', listening: '正在听…点一下停止', addSuggestion: '加入',
  guideErrors: { busy: '小海狸有点忙，请过半分钟再试', table_limit: '问得有点多，请稍后再试', store_limit: '今天的提问已达上限，请问服务员', no_model: 'AI 向导暂时不可用，请问服务员', offline: '连不上网络，请稍后再试', failed: '没听明白，请换个说法再试', no_speech_service: '语音暂不可用，请打字', too_long: '录音太长了' },
  aiNote: 'AI 回答仅供参考，过敏请务必告诉服务员。',
  bill: '买单', billTitle: '账单', noBill: '这桌还没有账单', taxActual: '税', alreadyPaid: '已付', balance: '待付',
  payServer: '请找服务员买单', allPaid: '这桌已经付清了，谢谢！', howMuch: '付多少', payAll: '全部', split: 'AA 平分', customAmount: '自定金额',
  perPerson: (m) => `每人 ${m}`, tipTitle: '小费', tipBasis: (m) => `按税前 ${m} 计算`, customTip: '自定', noTip: '不加小费',
  chooseTip: '请先选择小费', payButton: (m) => `付款 ${m}`, payNow: '付款', confirming: '正在确认付款…',
  paidOk: (m) => `已付款 ${m}，谢谢！`, stillOwed: (m) => `这桌还差 ${m}`, payMore: '再付一份',
  payFailed: '付款没有成功，没有扣款。', payReview: '付款需要店员核对，请把这个页面给服务员看。',
  payPending: '还没收到付款确认。请不要重复付款，找服务员确认一下。', nuveiLocale: 'zh',
  emailLabel: '收据邮箱', emailHint: '只交给支付公司 Nuvei 用于这笔付款，本店不保存。', enterEmail: '请填写邮箱',
  billErrors: { bad_amount: '金额超过了待付金额，账单已刷新', being_paid: '同桌有人正在付款，请稍后再看', no_open_check: '这桌的账单已经结了',
    online_pay_off: '这家店暂不支持手机付款，请找服务员', too_many_attempts: '尝试太多次了，请找服务员', bad_tip: '小费金额不对',
    gateway_error: '付款服务暂时连不上，请稍后再试', offline: '连不上网络，请稍后重试', failed: '没有成功，请稍后再试', bad_email: '邮箱格式不对' },
  search: '搜索菜品', noMatch: '没有找到这道菜', ordered: (n) => `已点 ×${n}`, from: '起', soldOutShort: '售完',
  avoid: '我的忌口', avoidTitle: '选择你不吃的', avoidHint: '含这些的菜会标红；没标注过敏原的菜会提示「未标注」。', avoidDone: '好了', avoidClear: '清除',
  hasAvoid: (a) => `含${a}`, unknownAvoid: '过敏原未标注',
  stepSent: '已提交', stepConfirm: '店员确认', stepKitchen: '已送厨房', orderNo: (n) => `第 ${n} 单`,
  call: '呼叫', callTitle: '需要什么？', callWater: '加水', callBill: '要买单', callHelp: '叫服务员',
  callSent: '已通知服务员，马上过来', callAck: '服务员已经在路上', callCancel: '取消呼叫', callAgain: '再叫一次',
  callErrors: { too_many_calls: '叫得有点多，服务员马上就来', offline: '连不上网络，请举手示意服务员', failed: '没有叫到，请举手示意服务员' },
  poweredBy: '技术支持', lang: 'EN', details: '详情', close: '关闭', ingredients: '配料',
  yourTable: '你的桌号是', pickLang: '选择语言', yourName: '你的名字（选填）', nameHint: '同桌的人会看到是谁点的菜', start: '开始点餐',
  tableOrders: '这桌的点单', checkNo: (n) => `账单 #${n}`, nothingYet: '这桌还没有点菜', inKitchen: '已下厨', roundN: (n) => `第 ${n} 轮`,
  orderMoreBtn: '继续加菜', payBtn: '买单', sentBy: (n) => `${n} 提交`, me: '我', guest: '客人',
  tableCart: '这桌的购物车', clearCart: '清空', confirmClear: '确定清空？', cartChanged: '同桌刚改了购物车，请再看一眼再提交',
  cartErrors: { sold_out: '刚刚售完：', item_unavailable: '这道菜已下架', bad_option: '请重新选择口味：', cart_full: '购物车满了，先提交一部分吧', offline: '连不上网络，请稍后重试', failed: '没有加上，请再试一次' },
  autoKitchen: '已直接送到厨房', allCategories: '全部分类', menuTitle: '设置', language: '语言', changeName: '我的名字', saveName: '保存',
} : {
  scanFirst: 'Scan the QR code on your table to order', badCode: 'This QR code is no longer valid. Please ask a server.', loading: 'Loading the menu…', offline: 'No connection. Please try again.',
  table: 'Table', all: 'All', soldOut: 'Sold out', add: 'Add', required: 'Required', pickOne: 'Pick one', upTo: (n) => `Up to ${n}`, optional: 'Optional',
  order: 'Your order', items: (n) => `${n} items`, subtotal: 'Subtotal', tax: 'Tax (est.)', total: 'Total', note: 'Note for the kitchen', notePh: 'e.g. less spicy, no green onion',
  itemNote: 'Note for this dish', send: 'Send to staff', sending: 'Sending…', save: 'Save', cancel: 'Cancel',
  confirmHint: 'A server confirms your order before it goes to the kitchen. Tap Bill to see and pay your bill.', myOrders: 'Your orders',
  pending: 'Waiting for a server to confirm', accepted: 'Sent to the kitchen', rejected: 'Not accepted', orderMore: 'Order more',
  errors: { too_many_orders: 'Too many orders at once. Please wait for a server.', sold_out: 'Just sold out: ', item_unavailable: 'An item is no longer available. Please reload the menu.', bad_option: 'Please choose the options again: ', unknown_table: 'This QR code is no longer valid. Please ask a server.' },
  failed: 'Your order was not sent', retry: 'Try again',
  contains: 'Contains', noAllergenData: 'Allergens not listed: ask your server',
  guide: 'Ask Beaver', guideTitle: 'Beaver · AI menu guide', guideHello: "Hi! I'm Beaver 🦫 Ask me in any language: what a dish is, how spicy, allergens, how much to order for your group.",
  guideHint: "e.g. I'm allergic to peanuts, what can I eat?", ask: 'Send', listening: 'Listening… tap to stop', addSuggestion: 'Add',
  guideErrors: { busy: 'Beaver is busy, try again in half a minute', table_limit: 'Lots of questions! Please wait a moment', store_limit: "Today's questions are used up, please ask your server", no_model: 'The AI guide is unavailable, please ask your server', offline: 'No connection, try again', failed: "Sorry, I didn't get that, try rephrasing", no_speech_service: 'Voice is unavailable, please type', too_long: 'That recording is too long' },
  aiNote: 'AI answers are a guide only. Always tell your server about allergies.',
  bill: 'Bill', billTitle: 'Bill', noBill: 'No bill at this table yet', taxActual: 'Tax', alreadyPaid: 'Paid', balance: 'Balance due',
  payServer: 'Please pay your server', allPaid: 'This table is all paid. Thank you!', howMuch: 'How much', payAll: 'All of it', split: 'Split evenly', customAmount: 'Other amount',
  perPerson: (m) => `${m} each`, tipTitle: 'Tip', tipBasis: (m) => `Of ${m} before tax`, customTip: 'Custom', noTip: 'No tip',
  chooseTip: 'Choose a tip first', payButton: (m) => `Pay ${m}`, payNow: 'Pay', confirming: 'Confirming your payment…',
  paidOk: (m) => `Paid ${m}. Thank you!`, stillOwed: (m) => `${m} still owed at this table`, payMore: 'Pay another share',
  payFailed: 'The payment did not go through. You were not charged.', payReview: 'A server needs to check this payment. Please show them this screen.',
  payPending: "We haven't had the confirmation yet. Please don't pay again: ask your server.", nuveiLocale: 'en',
  emailLabel: 'Email for your receipt', emailHint: 'Goes only to Nuvei, the payment company, for this payment. The store does not keep it.', enterEmail: 'Enter your email',
  billErrors: { bad_amount: 'That is more than the balance. The bill was refreshed.', being_paid: 'Someone at your table is paying right now. Try again in a moment.', no_open_check: 'This bill is already closed.',
    online_pay_off: "This store doesn't take payment on the phone. Please pay your server.", too_many_attempts: 'Too many tries. Please pay your server.', bad_tip: 'That tip amount is not allowed.',
    gateway_error: "Payment isn't available right now. Try again in a moment.", offline: 'No connection. Please try again.', failed: 'That did not work. Please try again.', bad_email: 'That email address does not look right.' },
  search: 'Search the menu', noMatch: 'No dish matches', ordered: (n) => `Ordered ×${n}`, from: '', soldOutShort: 'Sold out',
  avoid: 'My allergies', avoidTitle: "What you don't eat", avoidHint: "Dishes with these get a red label; dishes without allergen info say so.", avoidDone: 'Done', avoidClear: 'Clear',
  hasAvoid: (a) => `Contains ${a}`, unknownAvoid: 'Allergens not listed',
  stepSent: 'Sent', stepConfirm: 'Server confirms', stepKitchen: 'In the kitchen', orderNo: (n) => `Order ${n}`,
  call: 'Call', callTitle: 'What do you need?', callWater: 'Water', callBill: 'Ready to pay', callHelp: 'A server',
  callSent: 'A server has been told and is coming', callAck: 'A server is on the way', callCancel: 'Cancel the call', callAgain: 'Call again',
  callErrors: { too_many_calls: 'Lots of calls! A server is coming', offline: 'No connection. Please wave to a server.', failed: 'The call did not go through. Please wave to a server.' },
  poweredBy: 'Powered by', lang: '中文', details: 'Details', close: 'Close', ingredients: 'Ingredients',
  yourTable: 'Your table is', pickLang: 'Language', yourName: 'Your name (optional)', nameHint: 'So your table can see who ordered what', start: 'Start ordering',
  tableOrders: "Your table's order", checkNo: (n) => `Check #${n}`, nothingYet: 'Nothing ordered at this table yet', inKitchen: 'In the kitchen', roundN: (n) => `Round ${n}`,
  orderMoreBtn: 'Order more', payBtn: 'Bill', sentBy: (n) => `Sent by ${n}`, me: 'Me', guest: 'Guest',
  tableCart: "Your table's cart", clearCart: 'Clear', confirmClear: 'Clear it all?', cartChanged: 'Someone at your table just changed the cart. Please check it, then send.',
  cartErrors: { sold_out: 'Just sold out: ', item_unavailable: 'That dish is no longer available', bad_option: 'Please choose the options again: ', cart_full: 'The cart is full. Send some first.', offline: 'No connection. Please try again.', failed: "That didn't go in. Please try again." },
  autoKitchen: 'Sent straight to the kitchen', allCategories: 'All categories', menuTitle: 'Settings', language: 'Language', changeName: 'My name', saveName: 'Save',
}

// Canada's priority allergens (products.attributes.allergens codes).
const ALLERGENS = zh
  ? { peanut: '花生', tree_nut: '坚果', milk: '奶', egg: '蛋', wheat: '小麦/麸质', soy: '大豆', fish: '鱼', crustacean: '甲壳类', mollusc: '贝类', sesame: '芝麻', mustard: '芥末', sulphite: '亚硫酸盐' }
  : { peanut: 'peanut', tree_nut: 'tree nuts', milk: 'milk', egg: 'egg', wheat: 'wheat/gluten', soy: 'soy', fish: 'fish', crustacean: 'shellfish', mollusc: 'molluscs', sesame: 'sesame', mustard: 'mustard', sulphite: 'sulphites' }
const DIETARY = zh
  ? { vegetarian: '🌱 素食', vegan: '🌿 纯素', halal: '☪ 清真', gluten_free: '无麸质', no_pork: '不含猪肉', no_beef: '不含牛肉' }
  : { vegetarian: '🌱 Vegetarian', vegan: '🌿 Vegan', halal: '☪ Halal', gluten_free: 'Gluten-free', no_pork: 'No pork', no_beef: 'No beef' }

/** A dish's name in the customer's language (Cael's attributes.name_i18n), else the store's own. */
const dishName = (p) => (p.names && typeof p.names[LANG] === 'string' && p.names[LANG].trim()) || p.name
/** The store's own name under a translated one, so staff and customer can point at the same dish. */
const dishSubName = (p) => (dishName(p) !== p.name ? p.name : null)

/** Beaver, GoldSky's mascot: one picture per state (docs/mascot, web copies in public/beaver). */
function Beaver({ mood = 'idle', className = 'w-24', bob = false }) {
  return <img src={`/beaver/${mood}.webp`} alt="" aria-hidden="true" draggable="false" className={`${className} select-none ${bob ? 'beaver-bob' : ''}`} />
}

/**
 * A dish photo that never shows the browser's broken-image mark: if it can't
 * load (bad link, a phone's stale cache, no network) it gives way to [fallback].
 */
function DishImg({ src, className, fallback = null, lazy = false }) {
  const [broken, setBroken] = useState(false)
  useEffect(() => setBroken(false), [src])
  if (!src || broken) return fallback
  return <img src={src} alt="" loading={lazy ? 'lazy' : undefined} onError={() => setBroken(true)} className={className} />
}

/** The customer's allergy list as words, against one dish: null = nothing to warn about. */
function avoidWarning(p, avoid) {
  if (!avoid.length) return null
  if (!Array.isArray(p.allergens)) return { unknown: true }
  const hit = avoid.filter((a) => p.allergens.includes(a))
  return hit.length ? { hit } : null
}

/** Diet, spice and allergen badges; a dish with no allergen data says so. */
function DishFacts({ p, compact = false }) {
  return (
    <div className="flex flex-wrap gap-1 mt-1.5">
      {(p.dietary || []).map((d) => <span key={d} className="text-[11px] font-bold bg-emerald-50 text-emerald-700 px-2 py-0.5 rounded-full">{DIETARY[d] || d}</span>)}
      {p.spice_level > 0 && <span className="text-[11px] font-bold bg-red-50 text-red-700 px-2 py-0.5 rounded-full">{'🌶'.repeat(Math.min(p.spice_level, 5))}</span>}
      {!compact && (Array.isArray(p.allergens)
        ? p.allergens.length > 0 && <span className="text-[11px] font-bold bg-gold-50 text-gold-800 px-2 py-0.5 rounded-full">{T.contains}: {p.allergens.map((a) => ALLERGENS[a] || a).join(', ')}</span>
        : <span className="text-[11px] text-ink-mute px-1 py-0.5">{T.noAllergenData}</span>)}
    </div>
  )
}

/** Red label when the dish has something on the customer's list; grey when its allergens aren't recorded. */
function AvoidLabel({ w }) {
  if (!w) return null
  if (w.unknown) return <span className="self-start inline-block mt-1.5 text-[11px] font-bold text-ink-mute bg-slate-100 px-2 py-0.5 rounded-full">⚠ {T.unknownAvoid}</span>
  return <span className="self-start inline-block mt-1.5 text-[11px] font-black text-white bg-red-600 px-2 py-0.5 rounded-full">⚠ {T.hasAvoid(w.hit.map((a) => ALLERGENS[a] || a).join(zh ? '、' : ', '))}</span>
}

const store = {
  get(k) { try { return JSON.parse(localStorage.getItem(k) || 'null') } catch { return null } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)) } catch { /* private mode: fine */ } },
}

const newRef = () => (crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random().toString(16).slice(2))


/** A sheet that slides up from the bottom (centred on a wide screen). */
function Sheet({ onClose, children, tall = false }) {
  return (
    <div className="fixed inset-0 z-50 bg-ink/60 flex items-end sm:items-center sm:justify-center" onClick={onClose}>
      <div className={`bg-white w-full max-w-lg rounded-t-[28px] sm:rounded-[28px] ${tall ? 'max-h-[92vh]' : 'max-h-[88vh]'} flex flex-col overflow-hidden shadow-lift`}
        onClick={(e) => e.stopPropagation()}>
        {children}
      </div>
    </div>
  )
}

/**
 * A dish: photo, facts, then its options. Every dish opens here (tap the row);
 * pick-one groups act as radios, others as toggles up to max; required ones must
 * be answered before Add lights up.
 */
function DishSheet({ product, avoid, onAdd, onClose }) {
  const groups = product.modifier_groups || []
  const [picked, setPicked] = useState({})
  const [note, setNote] = useState('')
  const [qty, setQty] = useState(1)
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
  const sub = dishSubName(product)
  return (
    <Sheet onClose={onClose} tall>
      <div className="overflow-y-auto flex-1">
        {product.image_url ? (
          <div className="relative">
            <DishImg src={product.image_url} className="w-full aspect-[4/3] object-cover bg-slate-100" fallback={<div className="h-14" />} />
            <button onClick={onClose} aria-label={T.close} className="absolute top-3 left-3 bg-white/90 rounded-full p-2 shadow"><X className="w-5 h-5" /></button>
          </div>
        ) : (
          <div className="flex justify-end px-3 pt-3"><button onClick={onClose} aria-label={T.close} className="p-2 text-ink-mute"><X className="w-6 h-6" /></button></div>
        )}
        <div className="px-5 pt-4 pb-2">
          <h2 className="font-black text-2xl text-ink leading-tight">{dishName(product)}</h2>
          {sub && <p className="text-sm text-ink-mute font-medium">{sub}</p>}
          <p className="text-gold-700 font-black text-lg mt-1">{money(product.price_cents)}</p>
          <DishFacts p={product} />
          {/* Unknown allergens are already said by DishFacts here; only a real hit adds a label. */}
          <AvoidLabel w={avoidWarning(product, avoid)?.hit ? avoidWarning(product, avoid) : null} />
          {product.description && <p className="text-[15px] text-ink-soft mt-3">{product.description}</p>}
          {product.ingredients && <p className="text-sm text-ink-mute mt-2"><span className="font-bold">{T.ingredients}:</span> {product.ingredients}</p>}
          {product.guide && <p className="text-sm text-sky-800 bg-sky-50 rounded-xl px-3 py-2 mt-3">💡 {product.guide}</p>}
        </div>
        <div className="px-5 space-y-5 pb-4 pt-2">
          {groups.map((g) => {
            const need = missing.includes(g)
            return (
              <div key={g.id}>
                <div className="flex justify-between items-baseline mb-2">
                  <h3 className="font-black text-ink">{g.name}</h3>
                  <span className={`text-xs font-black px-2 py-0.5 rounded-full ${need ? 'bg-red-50 text-red-600' : g.required ? 'bg-slate-100 text-ink-mute' : 'text-ink-mute'}`}>
                    {g.required ? `${T.required}${g.max_select === 1 ? ' · ' + T.pickOne : ''}` : g.max_select === 1 ? T.pickOne : g.max_select ? T.upTo(g.max_select) : T.optional}
                  </span>
                </div>
                <div className="flex flex-wrap gap-2">
                  {g.options.map((o) => {
                    const on = (picked[g.id] || []).includes(o.id)
                    return (
                      <button key={o.id} onClick={() => toggle(g, o)}
                        className={`px-4 py-2.5 rounded-2xl text-sm font-bold border-2 transition-all ${on ? 'bg-gold-50 text-ink border-gold-500' : 'bg-white text-ink-soft border-slate-200'}`}>
                        {on && <Check className="w-4 h-4 inline -mt-0.5 mr-1 text-gold-700" />}{o.name}{o.price_cents ? ` +${money(o.price_cents)}` : ''}
                      </button>
                    )
                  })}
                </div>
              </div>
            )
          })}
          <div>
            <h3 className="font-black text-ink mb-2">{T.itemNote}</h3>
            <input value={note} onChange={(e) => setNote(e.target.value.slice(0, 120))} placeholder={T.notePh}
              className="w-full px-4 py-3 rounded-2xl bg-paper border border-slate-200 focus:border-gold-500 outline-none" />
          </div>
        </div>
      </div>
      <div className="p-4 border-t border-slate-100 flex items-center gap-3">
        <div className="flex items-center gap-2 shrink-0">
          <button onClick={() => setQty((q) => Math.max(1, q - 1))} className="w-10 h-10 rounded-full bg-slate-100 flex items-center justify-center" aria-label="-"><Minus className="w-4 h-4" /></button>
          <span className="font-black w-5 text-center text-lg">{qty}</span>
          <button onClick={() => setQty((q) => Math.min(20, q + 1))} className="w-10 h-10 rounded-full bg-slate-100 flex items-center justify-center" aria-label="+"><Plus className="w-4 h-4" /></button>
        </div>
        <button disabled={missing.length > 0 || product.sold_out}
          onClick={() => onAdd({ optionIds: chosen.map((o) => o.id), optionNames: chosen.map((o) => o.name), unit, note: note.trim() }, qty)}
          className="btn-gold flex-1 py-4 rounded-2xl text-lg">
          {product.sold_out ? T.soldOut : `${T.add} · ${money(unit * qty)}`}
        </button>
      </div>
    </Sheet>
  )
}

/** One order's way to the kitchen: sent -> server confirms -> kitchen, or the refusal and its reason. */
function OrderProgress({ o, n }) {
  const who = o.guest_name ? T.sentBy(o.guest_name) : null
  const rejected = o.status === 'REJECTED'
  const step = o.status === 'ACCEPTED' ? 3 : 2   // 1 sent (done), 2 waiting for the server, 3 in the kitchen
  const steps = [T.stepSent, T.stepConfirm, T.stepKitchen]
  return (
    <div className={`bg-white rounded-3xl shadow-card p-4 ${rejected ? 'ring-1 ring-red-200' : ''}`}>
      <div className="flex items-center gap-3">
        {!rejected && step === 2 && <Beaver mood="clipboard" className="w-14 shrink-0" bob />}
        {!rejected && step === 3 && <Beaver mood="done" className="w-12 shrink-0" />}
        {rejected && <Beaver mood="alert" className="w-12 shrink-0" />}
        <div className="min-w-0 flex-1">
          <div className="flex justify-between items-baseline">
            <span className="font-black text-ink">{T.orderNo(n)}</span>
            <span className="font-black text-ink">{money(o.subtotal_cents + o.tax_cents)}</span>
          </div>
          {rejected
            ? <p className="text-red-600 font-bold text-sm flex items-center gap-1"><XCircle className="w-4 h-4" />{T.rejected}{o.reject_reason ? ` · ${o.reject_reason}` : ''}</p>
            : <p className={`text-sm font-bold ${step === 3 ? 'text-sky-700' : 'text-gold-700'}`}>{step === 3 ? T.accepted : T.pending}</p>}
        </div>
      </div>
      {!rejected && (
        <div className="flex items-center mt-3" aria-hidden="true">
          {steps.map((label, i) => {
            const done = i + 1 < step || (step === 3 && i === 2)
            const active = i + 1 === step && step !== 3
            return (
              <React.Fragment key={label}>
                {i > 0 && <div className={`flex-1 h-1 rounded-full mx-1 ${i + 1 <= step ? 'bg-sky-500' : 'bg-slate-200'}`} />}
                <div className="flex flex-col items-center w-16">
                  <div className={`w-6 h-6 rounded-full flex items-center justify-center ${done ? 'bg-sky-500 text-white' : active ? 'bg-gold-500 text-ink animate-pulse' : 'bg-slate-200 text-slate-400'}`}>
                    {done ? <Check className="w-4 h-4" /> : <span className="text-[11px] font-black">{i + 1}</span>}
                  </div>
                  <span className={`text-[11px] mt-1 font-bold text-center leading-tight ${done || active ? 'text-ink' : 'text-ink-mute'}`}>{label}</span>
                </div>
              </React.Fragment>
            )
          })}
        </div>
      )}
      <p className="text-xs text-ink-mute mt-3 line-clamp-2">{who && <span className="font-bold text-ink-soft">{who} · </span>}{o.items.map((l) => `${l.quantity}× ${l.name}`).join(' · ')}</p>
    </div>
  )
}

/** Call a server: water, the bill, or just someone. One open call per table; it can be cancelled. */
function CallSheet({ call, busy, error, onCall, onCancel, onClose }) {
  const open = call?.status === 'OPEN'
  const acked = call?.status === 'ACKED'
  const reasons = [['WATER', '💧', T.callWater], ['BILL', '🧾', T.callBill], ['HELP', '🙋', T.callHelp]]
  return (
    <Sheet onClose={onClose}>
      <div className="px-5 pt-5 pb-6">
        <div className="flex items-start justify-between">
          <h2 className="font-black text-2xl text-ink">{open || acked ? (acked ? T.callAck : T.callSent) : T.callTitle}</h2>
          <button onClick={onClose} aria-label={T.close} className="p-1 text-ink-mute"><X className="w-6 h-6" /></button>
        </div>
        {open || acked ? (
          <div className="flex flex-col items-center text-center pt-4">
            <Beaver mood={acked ? 'done' : 'idle'} className="w-32" bob={!acked} />
            <p className="mt-3 text-lg font-black text-ink">{reasons.find((r) => r[0] === call.reason)?.[2]}</p>
            {open && <button onClick={onCancel} disabled={busy} className="mt-5 text-ink-mute font-bold underline underline-offset-4">{T.callCancel}</button>}
            {acked && <button onClick={() => onCall(call.reason)} disabled={busy} className="mt-5 text-sky-700 font-bold underline underline-offset-4">{T.callAgain}</button>}
          </div>
        ) : (
          <div className="grid grid-cols-3 gap-3 mt-5">
            {reasons.map(([code, icon, label]) => (
              <button key={code} disabled={busy} onClick={() => onCall(code)}
                className="flex flex-col items-center justify-center gap-2 py-5 rounded-3xl bg-gold-50 border-2 border-gold-200 active:scale-95 transition-transform">
                <span className="text-3xl">{icon}</span>
                <span className="font-black text-ink text-[15px]">{label}</span>
              </button>
            ))}
          </div>
        )}
        {error && <p className="mt-4 text-sm font-bold text-red-600 text-center">{error}</p>}
      </div>
    </Sheet>
  )
}

/** The customer's allergy list (kept on this phone only). */
function AvoidSheet({ avoid, setAvoid, onClose }) {
  const flip = (a) => setAvoid(avoid.includes(a) ? avoid.filter((x) => x !== a) : [...avoid, a])
  return (
    <Sheet onClose={onClose}>
      <div className="px-5 pt-5 pb-5">
        <h2 className="font-black text-2xl text-ink">{T.avoidTitle}</h2>
        <p className="text-sm text-ink-mute mt-1">{T.avoidHint}</p>
        <div className="flex flex-wrap gap-2 mt-4">
          {Object.entries(ALLERGENS).map(([code, label]) => {
            const on = avoid.includes(code)
            return (
              <button key={code} onClick={() => flip(code)}
                className={`px-4 py-2.5 rounded-2xl text-sm font-bold border-2 ${on ? 'bg-red-50 border-red-500 text-red-700' : 'bg-white border-slate-200 text-ink-soft'}`}>
                {on && <Check className="w-4 h-4 inline -mt-0.5 mr-1" />}{label}
              </button>
            )
          })}
        </div>
        <div className="flex gap-3 mt-6">
          <button onClick={() => setAvoid([])} className="px-5 py-3.5 rounded-2xl font-bold text-ink-soft bg-slate-100">{T.avoidClear}</button>
          <button onClick={onClose} className="btn-gold flex-1 py-3.5 rounded-2xl">{T.avoidDone}</button>
        </div>
      </div>
    </Sheet>
  )
}

/** Full-screen message with Beaver: bad code, offline, no token, loading. */
function BeaverScreen({ mood, text, action }) {
  return (
    <div className="relative min-h-screen flex flex-col items-center justify-center p-8 text-center bg-paper">
      <Beaver mood={mood} className="w-36" bob={mood === 'blink'} />
      <p className="mt-5 text-lg font-black text-ink max-w-xs">{text}</p>
      {action}
      <Signature className="absolute bottom-6" />
    </div>
  )
}

/** GoldSky's signature at the foot of every page: the store leads, we sign. */
function Signature({ className = '' }) {
  return (
    <div className={`flex items-center justify-center gap-1.5 text-[11px] text-ink-mute ${className}`}>
      <span>{T.poweredBy}</span>
      <span className="font-black tracking-tight gs-gradient-text text-[12px]">GoldSky Ourea</span>
    </div>
  )
}

/** A guest's round mark: their initial on a colour of their own (from the phone's id). */
const AVATAR_COLORS = ['#F59E0B', '#0EA5E9', '#10B981', '#8B5CF6', '#EF4444', '#EC4899', '#14B8A6', '#6366F1']
function Avatar({ name, id, mine, size = 'w-6 h-6 text-[11px]' }) {
  let h = 0
  for (const ch of String(id || name || '?')) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  const letter = mine ? T.me.charAt(0) : (name || '').trim().charAt(0).toUpperCase() || T.guest.charAt(0)
  return (
    <span title={mine ? T.me : name || T.guest} style={{ background: AVATAR_COLORS[h % AVATAR_COLORS.length] }}
      className={`${size} rounded-full text-white font-black flex items-center justify-center shrink-0 ring-2 ring-white`}>{letter}</span>
  )
}

/** The door: the store, the table, language and an optional name -- then the menu. */
function JoinScreen({ menu, guest, onJoin }) {
  const [name, setName] = useState(guest.name || '')
  const [logoBroken, setLogoBroken] = useState(false)
  const pick = (l) => { if ((l === 'zh') !== zh) { try { localStorage.setItem(LANG_KEY, l) } catch { /* fine */ } window.location.reload() } }
  return (
    <div className="relative min-h-screen bg-paper flex flex-col">
      <div className="bg-gradient-to-b from-gold-50 to-paper px-6 pt-10 pb-6 flex flex-col items-center text-center">
        {menu.store.logo_url && !logoBroken
          ? <img src={menu.store.logo_url} alt="" onError={() => setLogoBroken(true)} className="w-20 h-20 rounded-3xl object-cover bg-white shadow-card" />
          : <div className="w-20 h-20 rounded-3xl bg-gradient-to-br from-gold-400 to-gold-600 text-ink font-black text-4xl flex items-center justify-center shadow-card">{menu.store.name.trim().charAt(0)}</div>}
        <h1 className="mt-4 font-black text-2xl text-ink">{menu.store.name}</h1>
        <p className="mt-3 text-ink-soft font-bold">{T.yourTable}</p>
        <p className="text-5xl font-black text-sky-700 tracking-tight">{menu.table.name}</p>
      </div>
      <div className="px-6 max-w-md w-full mx-auto space-y-5">
        <div>
          <p className="text-sm font-black text-ink-soft mb-2">{T.pickLang}</p>
          <div className="grid grid-cols-2 gap-3">
            {[['zh', '中文'], ['en', 'English']].map(([l, label]) => {
              const on = (l === 'zh') === zh
              return <button key={l} onClick={() => pick(l)} className={`py-3.5 rounded-2xl font-black border-2 ${on ? 'bg-ink text-white border-ink' : 'bg-white text-ink-soft border-slate-200'}`}>{label}</button>
            })}
          </div>
        </div>
        <div>
          <input value={name} onChange={(e) => setName(e.target.value.slice(0, 30))} placeholder={T.yourName}
            className="w-full px-4 py-4 rounded-2xl bg-white border-2 border-slate-200 focus:border-gold-500 outline-none text-lg font-bold" />
          <p className="text-xs text-ink-mute mt-1.5 px-1">{T.nameHint}</p>
        </div>
        <button onClick={() => onJoin(name.trim())} className="btn-gold w-full py-4 rounded-2xl text-lg">{T.start}</button>
      </div>
      <div className="flex-1" />
      <div className="flex justify-center pb-2"><Beaver mood="idle" className="w-20" bob /></div>
      <Signature className="pb-6" />
    </div>
  )
}

/** The table's order, for everyone at it: waiting / refused QR orders, then what's on the check, round by round. */
function OrdersView({ menu, data, onMore, onBill, onClose }) {
  const orders = data?.orders || []
  const open = orders.filter((o) => o.status !== 'ACCEPTED')
  const lines = data?.check?.lines || []
  const rounds = [...new Set(lines.map((l) => l.round))].sort((a, b) => a - b)
  const byId = Object.fromEntries(menu.products.map((p) => [p.id, p]))
  const n = orders.length
  return (
    <div className="fixed inset-0 z-40 bg-paper flex flex-col">
      <div className="bg-white border-b border-slate-100 px-4 py-3 flex items-center gap-3">
        <button onClick={onClose} aria-label={T.close} className="w-10 h-10 rounded-full bg-slate-100 flex items-center justify-center"><X className="w-5 h-5" /></button>
        <div className="min-w-0">
          <h1 className="font-black text-lg text-ink leading-tight">{T.tableOrders}</h1>
          <p className="text-xs font-bold text-ink-mute">{T.table} {menu.table.name}{data?.check ? ` · ${T.checkNo(data.check.check_no)}` : ''}</p>
        </div>
      </div>
      <div className="flex-1 overflow-y-auto">
        <div className="max-w-3xl mx-auto px-4 py-4 space-y-3">
          {open.length === 0 && lines.length === 0 && (
            <div className="flex flex-col items-center text-center py-14">
              <Beaver mood="idle" className="w-32" bob />
              <p className="mt-3 font-black text-ink-soft">{T.nothingYet}</p>
            </div>
          )}
          {open.map((o) => <OrderProgress key={o.id} o={o} n={n - orders.indexOf(o)} />)}
          {lines.length > 0 && (
            <div className="bg-white rounded-3xl shadow-card overflow-hidden">
              <div className="px-4 pt-4 pb-2 flex items-center gap-2">
                <Beaver mood="done" className="w-10" />
                <h2 className="font-black text-ink">{T.inKitchen}</h2>
                <span className="ml-auto text-sm font-bold text-ink-mute">{T.items(lines.reduce((s, l) => s + l.quantity, 0))}</span>
              </div>
              {rounds.map((r) => (
                <div key={r} className="px-4 pb-3">
                  {rounds.length > 1 && <p className="text-[11px] font-black text-sky-700 bg-sky-50 inline-block px-2 py-0.5 rounded-full mt-1 mb-1">{T.roundN(r)}</p>}
                  {lines.filter((l) => l.round === r).map((l, i) => {
                    const p = byId[l.product_id]
                    return (
                      <div key={i} className="flex items-center gap-3 py-2 border-t border-slate-100 first:border-t-0">
                        <DishImg src={p?.image_url} className="w-12 h-12 rounded-xl object-cover bg-slate-100 shrink-0" fallback={<div className="w-12 h-12 rounded-xl bg-gold-50 shrink-0" />} />
                        <div className="min-w-0 flex-1">
                          <p className="font-bold text-ink leading-snug">{p ? dishName(p) : l.name}</p>
                          {Array.isArray(l.options) && l.options.length > 0 && <p className="text-xs text-ink-mute">{l.options.join(', ')}</p>}
                        </div>
                        <span className="font-black text-ink-soft">×{l.quantity}</span>
                      </div>
                    )
                  })}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
      <div className="bg-white border-t border-slate-100 p-4 flex gap-3 w-full max-w-3xl mx-auto">
        <button onClick={onMore} className="flex-1 py-4 rounded-2xl font-black text-lg border-2 border-gold-500 text-gold-800 bg-white">{T.orderMoreBtn}</button>
        <button onClick={onBill} className="btn-gold flex-1 py-4 rounded-2xl text-lg">{T.payBtn}</button>
      </div>
    </div>
  )
}

/** Every category at once, to jump straight to one. */
function CategorySheet({ sections, active, onPick, onClose }) {
  return (
    <Sheet onClose={onClose}>
      <div className="px-5 pt-5 pb-6">
        <div className="flex items-center justify-between mb-3">
          <h2 className="font-black text-xl text-ink">{T.allCategories}</h2>
          <button onClick={onClose} aria-label={T.close} className="p-1 text-ink-mute"><X className="w-6 h-6" /></button>
        </div>
        <div className="grid grid-cols-2 gap-2">
          {sections.map(({ c, items }) => (
            <button key={c} onClick={() => onPick(c)}
              className={`text-left px-4 py-3 rounded-2xl border-2 font-black ${active === c ? 'border-gold-500 bg-gold-50 text-ink' : 'border-slate-200 text-ink-soft'}`}>
              {c}<span className="block text-xs font-bold text-ink-mute">{T.items(items.length)}</span>
            </button>
          ))}
        </div>
      </div>
    </Sheet>
  )
}

/** The ≡ menu: language and the name shown to the table. */
function MenuSheet({ guest, onName, onClose }) {
  const [name, setName] = useState(guest.name || '')
  const pick = (l) => { if ((l === 'zh') !== zh) { try { localStorage.setItem(LANG_KEY, l) } catch { /* fine */ } window.location.reload() } }
  return (
    <Sheet onClose={onClose}>
      <div className="px-5 pt-5 pb-6 space-y-5">
        <div className="flex items-center justify-between">
          <h2 className="font-black text-xl text-ink">{T.menuTitle}</h2>
          <button onClick={onClose} aria-label={T.close} className="p-1 text-ink-mute"><X className="w-6 h-6" /></button>
        </div>
        <div>
          <p className="text-sm font-black text-ink-soft mb-2 flex items-center gap-1"><Globe className="w-4 h-4" />{T.language}</p>
          <div className="grid grid-cols-2 gap-3">
            {[['zh', '中文'], ['en', 'English']].map(([l, label]) => {
              const on = (l === 'zh') === zh
              return <button key={l} onClick={() => pick(l)} className={`py-3 rounded-2xl font-black border-2 ${on ? 'bg-ink text-white border-ink' : 'bg-white text-ink-soft border-slate-200'}`}>{label}</button>
            })}
          </div>
        </div>
        <div>
          <p className="text-sm font-black text-ink-soft mb-2">{T.changeName}</p>
          <div className="flex gap-2">
            <input value={name} onChange={(e) => setName(e.target.value.slice(0, 30))} placeholder={T.yourName}
              className="flex-1 px-4 py-3 rounded-2xl bg-paper border border-slate-200 focus:border-gold-500 outline-none font-bold" />
            <button onClick={() => { onName(name.trim()); onClose() }} className="btn-gold px-5 rounded-2xl">{T.saveName}</button>
          </div>
        </div>
        <Signature />
      </div>
    </Sheet>
  )
}

export default function App() {
  const token = useMemo(() => new URLSearchParams(window.location.search).get('t') || '', [])
  const [menu, setMenu] = useState(null)        // {table, store, products}
  const [state, setState] = useState(token ? 'loading' : 'no-token')  // loading | ready | bad-code | offline | no-token
  // This phone at the table: a random id (marks its cart lines) and the optional name.
  const [guest, setGuestState] = useState(() => {
    const g = store.get('ourea_scan_guest')
    if (g?.id) return g
    const fresh = { id: newRef(), name: '' }
    store.set('ourea_scan_guest', fresh)
    return fresh
  })
  const setGuestName = (name) => { const g = { ...guest, name }; setGuestState(g); store.set('ourea_scan_guest', g) }
  const [joined, setJoined] = useState(() => !!store.get('ourea_scan_joined_' + token))
  const [cart, setCart] = useState({ rev: 0, lines: [] })   // the table's shared cart (scan_cart)
  const [table, setTable] = useState(null)      // the table's orders and check (scan_table_orders)
  const [view, setView] = useState(null)        // null until the first read decides; 'menu' | 'orders'
  const [dishFor, setDishFor] = useState(null)
  const [cartOpen, setCartOpen] = useState(false)
  const [confirmClear, setConfirmClear] = useState(false)
  const [orderNote, setOrderNote] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState(null)
  const [toast, setToast] = useState(null)
  const [clientRef, setClientRef] = useState(newRef)  // kept across retries: one send = one order
  const [guideOpen, setGuideOpen] = useState(false)
  const [billOpen, setBillOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const [catsOpen, setCatsOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [avoid, setAvoidState] = useState(() => store.get('ourea_scan_avoid') || [])
  const [avoidOpen, setAvoidOpen] = useState(false)
  const [activeCat, setActiveCat] = useState(null)
  const [call, setCall] = useState(null)        // the table's latest call {id, reason, status}
  const [callOpen, setCallOpen] = useState(false)
  const [callBusy, setCallBusy] = useState(false)
  const [callError, setCallError] = useState(null)
  const [logoBroken, setLogoBroken] = useState(false)
  const sectionRefs = useRef({})
  const tabsRef = useRef(null)
  const headerRef = useRef(null)
  // Where the sticky header ends: a section counts as reached when it comes up to there.
  const headerBottom = () => (headerRef.current?.getBoundingClientRect().bottom || 140) + 12

  const setAvoid = (a) => { setAvoidState(a); store.set('ourea_scan_avoid', a) }
  const flash = (msg) => { setToast(msg); setTimeout(() => setToast((t) => (t === msg ? null : t)), 3500) }

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

  const readCart = useCallback(async () => {
    const { data, error: e } = await supabase.rpc('scan_cart', { p_token: token })
    if (!e && data) setCart(data)
  }, [token])
  const readTable = useCallback(async () => {
    const { data, error: e } = await supabase.rpc('scan_table_orders', { p_token: token })
    if (!e && data) setTable(data)
    return !e ? data : undefined
  }, [token])

  // The table's cart and orders, kept fresh: everyone at the table changes them.
  useEffect(() => {
    if (state !== 'ready' || !joined) return
    let stop = false
    const tick = async () => { if (!stop && document.visibilityState === 'visible') { await Promise.all([readCart(), readTable()]) } }
    tick()
    const id = setInterval(tick, 4000)
    return () => { stop = true; clearInterval(id) }
  }, [state, joined, readCart, readTable])

  // First look after joining: a table that already ordered opens on its order (like a waiter's pad), else the menu.
  useEffect(() => {
    if (view || !joined || state !== 'ready' || table === null) return
    setView(((table.orders || []).length || (table.check?.lines || []).length) ? 'orders' : 'menu')
  }, [view, joined, state, table])

  // The table's server call: read once, then followed while one is open.
  const readCall = useCallback(async () => {
    const { data, error: e } = await supabase.rpc('scan_call_state', { p_token: token })
    if (!e) setCall(data || null)
  }, [token])
  useEffect(() => { if (token && state === 'ready') readCall() }, [token, state, readCall])
  useEffect(() => {
    if (call?.status !== 'OPEN') return
    const id = setInterval(readCall, 4000)
    return () => clearInterval(id)
  }, [call?.status, readCall])

  const callWaiter = async (reason) => {
    setCallBusy(true); setCallError(null)
    const { data, error: e } = await supabase.rpc('scan_call_waiter', { p_token: token, p_reason: reason })
    setCallBusy(false)
    if (e) { setCallError(T.callErrors.offline); return }
    if (!data?.success) {
      if (data?.message === 'unknown_table') { setState('bad-code'); return }
      setCallError(T.callErrors[data?.message] || T.callErrors.failed); return
    }
    await readCall()
  }
  const cancelCall = async () => {
    setCallBusy(true)
    const { error: e } = await supabase.rpc('scan_cancel_call', { p_token: token })
    setCallBusy(false)
    if (e) { setCallError(T.callErrors.offline); return }
    await readCall()
  }

  const categories = useMemo(() => menu ? [...new Set(menu.products.map((p) => p.category || '—'))] : [], [menu])
  const q = query.trim().toLowerCase()
  const matches = (p) => !q || [p.name, dishName(p), p.description, p.ingredients].some((x) => (x || '').toLowerCase().includes(q))
  const sections = useMemo(() => menu ? categories.map((c) => ({ c, items: menu.products.filter((p) => (p.category || '—') === c && matches(p)) })).filter((s) => s.items.length) : [],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [menu, categories, q])
  const byId = useMemo(() => Object.fromEntries((menu?.products || []).map((p) => [p.id, p])), [menu])

  // What the whole table already has, per dish: lines on the check plus QR orders still waiting.
  const orderedCount = useMemo(() => {
    const m = {}
    for (const l of table?.check?.lines || []) if (l.product_id) m[l.product_id] = (m[l.product_id] || 0) + l.quantity
    for (const o of table?.orders || []) if (o.status === 'PENDING') for (const l of o.items) m[l.product_id] = (m[l.product_id] || 0) + l.quantity
    return m
  }, [table])

  // Category tabs follow the scroll (the section nearest the top is the active one).
  useEffect(() => {
    if (!sections.length || view !== 'menu') return
    const onScroll = () => {
      let cur = sections[0].c
      for (const s of sections) {
        const el = sectionRefs.current[s.c]
        if (el && el.getBoundingClientRect().top <= headerBottom() + 4) cur = s.c
      }
      setActiveCat(cur)
    }
    onScroll()
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [sections, view])
  useEffect(() => {
    const tab = tabsRef.current?.querySelector(`[data-cat="${CSS.escape(activeCat || '')}"]`)
    tab?.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' })
  }, [activeCat])
  const jumpTo = (c) => {
    const el = sectionRefs.current[c]
    if (el) window.scrollTo({ top: el.getBoundingClientRect().top + window.scrollY - headerBottom(), behavior: 'smooth' })
  }

  // ---- the shared cart: every change goes to the server, which answers with the table's cart
  const cartError = (data) => {
    const m = data?.message
    if (m === 'unknown_table') { setState('bad-code'); return }
    flash((T.cartErrors[m] || T.cartErrors.failed) + (data?.product && (m === 'sold_out' || m === 'bad_option') ? data.product : ''))
    if (m === 'item_unavailable' || m === 'sold_out') loadMenu()
  }
  const addLine = async (product, { optionIds = [], note = '' } = {}, qty = 1) => {
    const { data, error: e } = await supabase.rpc('scan_cart_add', {
      p_token: token, p_guest_id: guest.id, p_guest_name: guest.name || null, p_product_id: product.id,
      p_option_ids: optionIds, p_note: note || null, p_quantity: Math.min(qty, 20),
    })
    if (e) { flash(T.cartErrors.offline); return }
    if (!data?.success) { cartError(data); return }
    setCart(data.cart); setError(null)
  }
  const setQty = async (line, quantity) => {
    const { data, error: e } = await supabase.rpc('scan_cart_set', { p_token: token, p_line_id: line.id, p_quantity: quantity })
    if (e) { flash(T.cartErrors.offline); return }
    if (data?.success) setCart(data.cart); else cartError(data)
  }
  const clearCart = async () => {
    setConfirmClear(false)
    const { data, error: e } = await supabase.rpc('scan_cart_clear', { p_token: token })
    if (e) { flash(T.cartErrors.offline); return }
    if (data?.success) setCart(data.cart)
  }

  // A cart line with what it shows: the dish, its option names, the display price.
  const lines = cart.lines.map((l) => {
    const p = byId[l.product_id]
    const opts = (p?.modifier_groups || []).flatMap((g) => g.options).filter((o) => l.option_ids.includes(o.id))
    return { ...l, p, optionNames: opts.map((o) => o.name), unit: (p?.price_cents || 0) + opts.reduce((s, o) => s + (o.price_cents || 0), 0) }
  })
  const inCart = (pid) => lines.filter((l) => l.product_id === pid).reduce((s, l) => s + l.quantity, 0)
  const count = lines.reduce((s, l) => s + l.quantity, 0)
  const subtotal = lines.reduce((s, l) => s + l.unit * l.quantity, 0)
  // Display estimate; the server prices the order (same rule: per rate, rounded once).
  const tax = Object.entries(lines.reduce((m, l) => { const b = l.p?.tax_rate_bps ?? 0; return { ...m, [b]: (m[b] || 0) + l.unit * l.quantity } }, {}))
    .reduce((s, [bps, amt]) => s + Math.round(amt * Number(bps) / 10000), 0)

  const submit = async () => {
    setSubmitting(true); setError(null)
    const { data, error: e } = await supabase.rpc('scan_submit_cart', {
      p_token: token, p_rev: cart.rev, p_note: orderNote.trim() || null, p_client_ref: clientRef, p_guest_name: guest.name || null,
    })
    setSubmitting(false)
    if (e) { setError(T.offline); return }
    if (!data?.success) {
      const m = data?.message
      if (m === 'cart_changed') { if (data.cart) setCart(data.cart); setError(T.cartChanged); return }
      setError((T.errors[m] || T.failed) + (data?.product && (m === 'sold_out' || m === 'bad_option') ? data.product : ''))
      if (m === 'unknown_table') setState('bad-code')
      if (m === 'item_unavailable' || m === 'sold_out') loadMenu()
      return
    }
    setOrderNote(''); setCartOpen(false); setClientRef(newRef())
    await Promise.all([readCart(), readTable()])
    setView('orders')
  }

  if (state === 'no-token') return <BeaverScreen mood="idle" text={T.scanFirst} />
  if (state === 'bad-code') return <BeaverScreen mood="alert" text={T.badCode} />
  if (state === 'offline') {
    return <BeaverScreen mood="alert" text={T.offline}
      action={<button onClick={loadMenu} className="btn-gold mt-6 px-8 py-3.5 rounded-2xl">{T.retry}</button>} />
  }
  if (state === 'loading' || !menu) return <BeaverScreen mood="blink" text={T.loading} />
  if (!joined) {
    return <JoinScreen menu={menu} guest={guest} onJoin={(name) => { setGuestName(name); store.set('ourea_scan_joined_' + token, true); setJoined(true) }} />
  }
  if (view === null) return <BeaverScreen mood="blink" text={T.loading} />

  const callOpenNow = call?.status === 'OPEN'
  const callAckedNow = call?.status === 'ACKED' && call.closed_at && Date.now() - new Date(call.closed_at).getTime() < 3 * 60 * 1000
  const waiting = (table?.orders || []).filter((o) => o.status === 'PENDING').length

  return (
    <div className="min-h-screen pb-32 bg-paper">
      {/* Store first: its logo and name lead the page. */}
      <header ref={headerRef} className="sticky top-0 z-30 bg-white/95 backdrop-blur border-b border-slate-100">
        <div className="max-w-3xl mx-auto px-4 py-2.5 flex items-center gap-3">
          {menu.store.logo_url && !logoBroken
            ? <img src={menu.store.logo_url} alt="" onError={() => setLogoBroken(true)} className="w-10 h-10 rounded-xl object-cover bg-slate-100 shrink-0" />
            : <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-gold-400 to-gold-600 text-ink font-black text-lg flex items-center justify-center shrink-0">{menu.store.name.trim().charAt(0)}</div>}
          <div className="min-w-0 flex-1">
            <h1 className="font-black text-[17px] text-ink truncate leading-tight">{menu.store.name}</h1>
            <span className="inline-block mt-0.5 bg-sky-50 text-sky-800 font-black text-xs px-2 py-0.5 rounded-full">{T.table} {menu.table.name}</span>
          </div>
          <button onClick={() => { setCallError(null); setCallOpen(true) }} aria-label={T.call}
            className={`shrink-0 w-10 h-10 rounded-full flex items-center justify-center ${callOpenNow ? 'bg-gold-500 text-ink' : 'bg-slate-100 text-ink'}`}>
            <Bell className={`w-5 h-5 ${callOpenNow ? 'bell-ring' : ''}`} />
          </button>
          <button onClick={() => setView('orders')} aria-label={T.tableOrders} className="shrink-0 relative w-10 h-10 rounded-full bg-slate-100 text-ink flex items-center justify-center">
            <ClipboardList className="w-5 h-5" />
            {waiting > 0 && <span className="absolute -top-1 -right-1 bg-gold-500 text-ink text-[10px] font-black min-w-[18px] h-[18px] px-1 rounded-full flex items-center justify-center">{waiting}</span>}
          </button>
          <button onClick={() => setMenuOpen(true)} aria-label={T.menuTitle} className="shrink-0 w-10 h-10 rounded-full bg-slate-100 text-ink flex items-center justify-center">
            <MenuIcon className="w-5 h-5" />
          </button>
        </div>
        {(callOpenNow || callAckedNow) && (
          <button onClick={() => setCallOpen(true)} className={`w-full text-sm font-bold py-1.5 ${callAckedNow ? 'bg-sky-50 text-sky-800' : 'bg-gold-50 text-gold-800'}`}>
            {callAckedNow ? T.callAck : T.callSent}
          </button>
        )}
        {/* Search, allergy filter, and the category tabs that follow the scroll. */}
        <div className="max-w-3xl mx-auto px-4 pb-2 flex gap-2">
          <label className="flex-1 flex items-center gap-2 bg-paper rounded-2xl px-3 py-2 border border-slate-200 focus-within:border-gold-500">
            <Search className="w-4 h-4 text-ink-mute shrink-0" />
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={T.search} className="bg-transparent outline-none w-full text-[15px]" />
            {query && <button onClick={() => setQuery('')} aria-label={T.close}><X className="w-4 h-4 text-ink-mute" /></button>}
          </label>
          <button onClick={() => setAvoidOpen(true)}
            className={`shrink-0 px-3 rounded-2xl text-sm font-black border flex items-center gap-1 ${avoid.length ? 'bg-red-50 border-red-300 text-red-700' : 'bg-white border-slate-200 text-ink-soft'}`}>
            <ShieldAlert className="w-4 h-4" />{T.avoid}{avoid.length ? ` ${avoid.length}` : ''}
          </button>
        </div>
        {categories.length > 1 && !q && (
          <div className="max-w-3xl mx-auto pl-4 pb-2.5 flex items-center gap-2">
            <nav ref={tabsRef} className="flex-1 overflow-x-auto no-scrollbar flex gap-2">
              {sections.map(({ c }) => (
                <button key={c} data-cat={c} onClick={() => jumpTo(c)}
                  className={`px-4 py-1.5 rounded-full text-sm font-black whitespace-nowrap transition-colors ${activeCat === c ? 'bg-ink text-white' : 'bg-slate-100 text-ink-soft'}`}>
                  {c}
                </button>
              ))}
            </nav>
            <button onClick={() => setCatsOpen(true)} aria-label={T.allCategories}
              className="shrink-0 mr-4 w-9 h-9 rounded-full bg-white border border-slate-200 flex items-center justify-center shadow-sm"><ChevronDown className="w-5 h-5" /></button>
          </div>
        )}
      </header>

      <main className="max-w-3xl mx-auto px-4 pt-2">
        {sections.length === 0 && (
          <div className="flex flex-col items-center py-16 text-center">
            <Beaver mood="thinking" className="w-28" />
            <p className="mt-3 font-bold text-ink-soft">{T.noMatch}</p>
          </div>
        )}
        {sections.map(({ c, items }) => (
          <section key={c} ref={(el) => { sectionRefs.current[c] = el }} className="pt-5">
            <h2 className="flex items-center gap-2 font-black text-lg text-ink mb-2">
              <span className="w-1.5 h-5 rounded-full bg-gradient-to-b from-gold-400 to-sky-500" />{c}
            </h2>
            <div className="bg-white rounded-3xl shadow-card divide-y divide-slate-100 overflow-hidden">
              {items.map((p) => {
                const w = avoidWarning(p, avoid)
                const ordered = orderedCount[p.id] || 0
                const here = inCart(p.id)
                const sub = dishSubName(p)
                return (
                  <div key={p.id} role="button" tabIndex={0} onClick={() => setDishFor(p)}
                    className={`flex gap-3 p-3 active:bg-slate-50 ${p.sold_out ? 'opacity-50' : ''}`}>
                    <DishImg src={p.image_url} lazy className="w-24 h-24 rounded-2xl object-cover bg-slate-100 shrink-0" />
                    <div className="min-w-0 flex-1 flex flex-col">
                      <h3 className="font-black text-ink leading-snug">{dishName(p)}</h3>
                      {sub && <p className="text-xs text-ink-mute">{sub}</p>}
                      {p.description && <p className="text-xs text-ink-mute mt-0.5 line-clamp-2">{p.description}</p>}
                      <DishFacts p={p} compact />
                      <AvoidLabel w={w} />
                      <div className="flex items-end justify-between mt-auto pt-2">
                        <div className="flex items-center gap-2">
                          <span className="font-black text-gold-700 text-[17px]">{money(p.price_cents)}{p.modifier_groups?.length ? <sup className="text-xs">+</sup> : null}</span>
                          {ordered > 0 && <span className="text-[11px] font-black bg-sky-50 text-sky-800 px-2 py-0.5 rounded-full">{T.ordered(ordered)}</span>}
                        </div>
                        {p.sold_out ? <span className="text-xs font-black text-ink-mute">{T.soldOutShort}</span> : (
                          <button onClick={(e) => { e.stopPropagation(); p.modifier_groups?.length ? setDishFor(p) : addLine(p) }}
                            className="relative w-9 h-9 rounded-full bg-gradient-to-br from-gold-400 to-gold-600 text-ink flex items-center justify-center shadow-[0_4px_12px_rgba(255,159,0,.35)] active:scale-90 transition-transform" aria-label={T.add}>
                            <Plus className="w-5 h-5" strokeWidth={3} />
                            {here > 0 && <span className="absolute -top-1.5 -right-1.5 bg-ink text-white text-[10px] font-black min-w-[18px] h-[18px] px-1 rounded-full flex items-center justify-center">{here}</span>}
                          </button>
                        )}
                      </div>
                    </div>
                  </div>
                )
              })}
            </div>
          </section>
        ))}
        <p className="text-xs text-ink-mute text-center mt-6 px-6">{T.confirmHint}</p>
        <Signature className="mt-3 mb-4" />
      </main>

      {count > 0 && (
        <button onClick={() => { setError(null); setConfirmClear(false); setCartOpen(true) }}
          className="fixed bottom-4 left-4 right-4 z-30 max-w-xl mx-auto bg-ink text-white pl-3 pr-5 py-3 rounded-[22px] shadow-lift flex items-center justify-between">
          <span className="flex items-center gap-3">
            <span className="bg-gradient-to-br from-gold-400 to-gold-600 text-ink font-black w-10 h-10 rounded-2xl flex items-center justify-center">{count}</span>
            <span className="flex -space-x-1.5">{[...new Map(lines.map((l) => [l.guest_id, l])).values()].slice(0, 4).map((l) => <Avatar key={l.guest_id} id={l.guest_id} name={l.guest_name} mine={l.guest_id === guest.id} />)}</span>
            <span className="font-black">{T.tableCart}</span>
          </span>
          <span className="flex items-center gap-1 font-black text-lg text-gold-300">{money(subtotal + tax)}<ChevronRight className="w-5 h-5 text-slate-400" /></span>
        </button>
      )}

      {cartOpen && (
        <Sheet onClose={() => setCartOpen(false)} tall>
          <div className="px-5 py-4 border-b border-slate-100 flex items-center justify-between gap-2">
            <h2 className="font-black text-xl min-w-0 truncate">{T.tableCart} · {T.table} {menu.table.name}</h2>
            <div className="flex items-center gap-1 shrink-0">
              {lines.length > 0 && (confirmClear
                ? <button onClick={clearCart} className="text-sm font-black text-red-600 px-2 py-1">{T.confirmClear}</button>
                : <button onClick={() => setConfirmClear(true)} className="text-sm font-bold text-ink-mute px-2 py-1">{T.clearCart}</button>)}
              <button onClick={() => setCartOpen(false)} aria-label={T.close} className="p-2 text-ink-mute"><X className="w-6 h-6" /></button>
            </div>
          </div>
          <div className="px-5 py-2 overflow-y-auto flex-1 divide-y divide-slate-100">
            {lines.length === 0 && <div className="flex flex-col items-center py-10"><Beaver mood="idle" className="w-24" /></div>}
            {lines.map((l) => (
              <div key={l.id} className="py-3 flex items-center justify-between gap-3">
                <div className="relative shrink-0">
                  <DishImg src={l.p?.image_url} className="w-14 h-14 rounded-xl object-cover bg-slate-100" fallback={<div className="w-14 h-14 rounded-xl bg-gold-50" />} />
                  <span className="absolute -bottom-1 -right-1"><Avatar id={l.guest_id} name={l.guest_name} mine={l.guest_id === guest.id} size="w-5 h-5 text-[10px]" /></span>
                </div>
                <div className="min-w-0 flex-1">
                  <p className="font-black text-ink">{l.p ? dishName(l.p) : '…'}</p>
                  {l.optionNames.length > 0 && <p className="text-xs text-sky-800">+ {l.optionNames.join(', ')}</p>}
                  {l.note && <p className="text-xs text-ink-mute italic">* {l.note}</p>}
                  <p className="text-xs text-ink-mute">{money(l.unit)} × {l.quantity}{l.guest_id === guest.id ? ` · ${T.me}` : l.guest_name ? ` · ${l.guest_name}` : ''}</p>
                </div>
                <div className="flex items-center gap-2.5 shrink-0">
                  <button onClick={() => setQty(l, l.quantity - 1)} aria-label="-" className="w-9 h-9 rounded-full bg-slate-100 flex items-center justify-center"><Minus className="w-4 h-4" /></button>
                  <span className="font-black w-4 text-center">{l.quantity}</span>
                  <button onClick={() => setQty(l, l.quantity + 1)} aria-label="+" disabled={l.quantity >= 20} className="w-9 h-9 rounded-full bg-gold-500 text-ink flex items-center justify-center"><Plus className="w-4 h-4" strokeWidth={3} /></button>
                </div>
              </div>
            ))}
            <div className="py-3">
              <label className="text-sm font-black text-ink-soft flex items-center gap-1 mb-2"><Edit3 className="w-4 h-4" />{T.note}</label>
              <input value={orderNote} onChange={(e) => setOrderNote(e.target.value.slice(0, 200))} placeholder={T.notePh}
                className="w-full px-4 py-3 rounded-2xl bg-paper border border-slate-200 focus:border-gold-500 outline-none" />
            </div>
          </div>
          <div className="p-5 bg-paper border-t border-slate-100 space-y-1.5">
            <div className="flex justify-between text-sm text-ink-soft"><span>{T.subtotal}</span><span className="font-bold">{money(subtotal)}</span></div>
            <div className="flex justify-between text-sm text-ink-soft"><span>{T.tax}</span><span className="font-bold">{money(tax)}</span></div>
            <div className="flex justify-between text-xl font-black pt-2 border-t border-slate-200"><span>{T.total}</span><span>{money(subtotal + tax)}</span></div>
            {error && <p className="text-sm font-bold text-red-600">{error}</p>}
            <p className="text-xs text-ink-mute">{T.confirmHint}</p>
            <div className="flex gap-3 pt-1">
              <button onClick={() => setCartOpen(false)} className="px-5 py-4 rounded-2xl font-black border-2 border-gold-500 text-gold-800 bg-white">{T.orderMoreBtn}</button>
              <button onClick={submit} disabled={submitting || count === 0}
                className="btn-gold flex-1 py-4 rounded-2xl text-lg flex items-center justify-center gap-2">
                {submitting && <Loader2 className="w-5 h-5 animate-spin" />}{submitting ? T.sending : T.send}
              </button>
            </div>
          </div>
        </Sheet>
      )}

      {/* Beaver, the AI guide: GoldSky's own, found on no other ordering page. */}
      <button onClick={() => setGuideOpen(true)}
        className={`fixed right-3 z-30 flex items-center gap-1 pl-1 pr-4 py-1 rounded-full bg-gradient-to-r from-sky-500 to-sky-600 text-white shadow-lift font-black ${count > 0 ? 'bottom-[92px]' : 'bottom-4'}`}>
        <span className="w-11 h-11 rounded-full bg-white flex items-end justify-center overflow-hidden"><Beaver mood="idle" className="w-10 translate-y-1" /></span>
        {T.guide}
      </button>

      {toast && <div className="fixed left-4 right-4 bottom-28 z-[60] max-w-md mx-auto bg-ink text-white text-sm font-bold px-4 py-3 rounded-2xl shadow-lift text-center">{toast}</div>}

      {view === 'orders' && (
        <OrdersView menu={menu} data={table} onClose={() => setView('menu')} onMore={() => setView('menu')} onBill={() => setBillOpen(true)} />
      )}

      {guideOpen && (
        <Guide token={token} products={menu.products} T={T} askGuide={askGuide} onClose={() => setGuideOpen(false)}
          onAdd={(s) => {
            const p = menu.products.find((x) => x.id === s.product_id)
            if (!p) return
            // Options the guide couldn't settle: the customer picks them.
            if (s.needs_options || (p.modifier_groups?.length && !s.option_ids.length)) { setGuideOpen(false); setDishFor(p); return }
            addLine(p, { optionIds: s.option_ids }, s.quantity)
          }} />
      )}

      {billOpen && <Bill token={token} T={T} supabase={supabase} callPay={callPay} onClose={() => setBillOpen(false)} />}
      {callOpen && <CallSheet call={callOpenNow || callAckedNow ? call : null} busy={callBusy} error={callError}
        onCall={callWaiter} onCancel={cancelCall} onClose={() => setCallOpen(false)} />}
      {avoidOpen && <AvoidSheet avoid={avoid} setAvoid={setAvoid} onClose={() => setAvoidOpen(false)} />}
      {menuOpen && <MenuSheet guest={guest} onName={setGuestName} onClose={() => setMenuOpen(false)} />}
      {catsOpen && <CategorySheet sections={sections} active={activeCat} onPick={(c) => { setCatsOpen(false); jumpTo(c) }} onClose={() => setCatsOpen(false)} />}
      {dishFor && (
        <DishSheet product={dishFor} avoid={avoid} onClose={() => setDishFor(null)}
          onAdd={(choice, qty) => { addLine(dishFor, choice, qty); setDishFor(null) }} />
      )}
    </div>
  )
}
