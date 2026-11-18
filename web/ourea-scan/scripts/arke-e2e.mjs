/**
 * Arke (online ordering) server-side checks against the Scan E2E store
 * (tools/scan-e2e/seed.sql + seed-arke.sql, link zz-scan-e2e), through the same
 * anon RPCs and the arke-pay function the customer page uses. Run
 * `psql "$DB" -v reset=1 -f tools/scan-e2e/seed-arke.sql` first: the abuse limits
 * count the last 10 minutes.
 *
 *   cd web/ourea-scan && node scripts/arke-e2e.mjs
 *
 * Leaves one unpaid order (AWAITING_PAYMENT) with an opened Nuvei sandbox session;
 * paying it is done in the browser (?o=<token>), not here.
 */
import { createClient } from '@supabase/supabase-js'

const SUPABASE_URL = process.env.VITE_SUPABASE_URL || 'https://ttbtxxpnvkcbyugzdqfw.supabase.co'
const SUPABASE_KEY = process.env.VITE_SUPABASE_KEY || 'sb_publishable_g4TGLHlVn3LfafRZFOAjnA_x9M0xyBi'
const sb = createClient(SUPABASE_URL, SUPABASE_KEY, { auth: { persistSession: false } })

const SLUG = 'zz-scan-e2e'
const P = (n) => 'e2e5ca00-0000-4000-8001-' + String(n).padStart(12, '0')
const NOODLE = P(1), ZHAJIANG = P(2), SOLD_OUT = P(5), WATER = P(7), RETIRED = P(9)

let failed = 0
const check = (name, ok, detail) => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${ok || detail === undefined ? '' : '  -> ' + JSON.stringify(detail)}`)
  if (!ok) failed++
}
const ref = () => 'arke-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
async function rpc(fn, args) {
  const { data, error } = await sb.rpc(fn, args)
  if (error) throw new Error(`${fn}: ${error.message}`)
  return data
}
const place = (items, o = {}) => rpc('online_place_order', {
  p_slug: o.slug ?? SLUG, p_items: items, p_name: o.name ?? 'Test Customer', p_phone: o.phone ?? '613-555-0123',
  p_note: o.note ?? null, p_client_ref: o.ref ?? ref(),
  ...(o.fulfilment ? { p_fulfilment: o.fulfilment, p_address: o.address ?? null } : {}),
  ...(o.when ? { p_scheduled_for: o.when } : {}),
})
const expectMsg = async (name, items, msg, o) => {
  const r = await place(items, o)
  check(name, r?.success === false && r.message === msg, r)
}
async function pay(body) {
  const res = await fetch(`${SUPABASE_URL}/functions/v1/arke-pay`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', apikey: SUPABASE_KEY }, body: JSON.stringify(body),
  })
  return res.json().catch(() => ({}))
}

// ---- the store's page
const menu = await rpc('online_menu', { p_slug: SLUG })
check('店铺页加载', menu?.store?.slug === SLUG && Array.isArray(menu.products), menu?.store)
check('现在接单 (open_now)', menu?.store?.open_now === true, menu?.store)
check('有线上付款 (Nuvei 沙盒)', menu?.store?.online_pay === true)
check('链接大小写都认', (await rpc('online_menu', { p_slug: ' ZZ-Scan-E2E ' }))?.store?.slug === SLUG)
const ids = new Set(menu.products.map((p) => p.id))
check('下架菜不出现', !ids.has(RETIRED))
check('售罄菜标记 sold_out', menu.products.find((p) => p.id === SOLD_OUT)?.sold_out === true)
check('加料组随菜返回', menu.products.find((p) => p.id === NOODLE)?.modifier_groups?.length === 2)
check('不存在的链接 -> null', (await rpc('online_menu', { p_slug: 'no-such-store' })) === null)

// ---- refused orders (none of these create an order)
await expectMsg('不存在的店 -> unknown_store', [{ product_id: ZHAJIANG, quantity: 1 }], 'unknown_store', { slug: 'no-such-store' })
await expectMsg('空 client_ref -> bad_request', [{ product_id: ZHAJIANG, quantity: 1 }], 'bad_request', { ref: '' })
await expectMsg('没写名字 -> name_required', [{ product_id: ZHAJIANG, quantity: 1 }], 'name_required', { name: '  ' })
await expectMsg('电话太短 -> bad_phone', [{ product_id: ZHAJIANG, quantity: 1 }], 'bad_phone', { phone: '555-1234' })
await expectMsg('空购物车 -> items_required', [], 'items_required')
await expectMsg('数量 0 -> bad_quantity', [{ product_id: ZHAJIANG, quantity: 0 }], 'bad_quantity')
await expectMsg('数量 21 -> bad_quantity', [{ product_id: ZHAJIANG, quantity: 21 }], 'bad_quantity')
await expectMsg('必选辣度没选 -> bad_option', [{ product_id: NOODLE, quantity: 1, option_ids: ['egg'] }], 'bad_option')
await expectMsg('别的菜的选项 -> bad_option', [{ product_id: ZHAJIANG, quantity: 1, option_ids: ['spice1'] }], 'bad_option')
await expectMsg('售罄菜 -> sold_out', [{ product_id: SOLD_OUT, quantity: 1 }], 'sold_out')
await expectMsg('下架菜 -> item_unavailable', [{ product_id: RETIRED, quantity: 1 }], 'item_unavailable')

// ---- a good order: the server prices it
const items = [
  { product_id: NOODLE, quantity: 2, option_ids: ['spice1', 'egg'], note: '少葱' },
  { product_id: ZHAJIANG, quantity: 1 },
  { product_id: WATER, quantity: 1 },
]
const myRef = ref()
const placed = await place(items, { ref: myRef, note: '多给餐具' })
check('下单成功（等待付款）', placed?.success === true && !!placed.token, placed)
const noodle = menu.products.find((p) => p.id === NOODLE)
const opt = (id) => noodle.modifier_groups.flatMap((g) => g.options).find((o) => o.id === id)?.price_cents || 0
const zj = menu.products.find((p) => p.id === ZHAJIANG), water = menu.products.find((p) => p.id === WATER)
const sub = 2 * (noodle.price_cents + opt('spice1') + opt('egg')) + zj.price_cents + water.price_cents
check('小计由服务器算', placed?.subtotal_cents === sub, { got: placed?.subtotal_cents, want: sub })
const byRate = {}
byRate[noodle.tax_rate_bps] = (byRate[noodle.tax_rate_bps] || 0) + 2 * (noodle.price_cents + opt('spice1') + opt('egg'))
byRate[zj.tax_rate_bps] = (byRate[zj.tax_rate_bps] || 0) + zj.price_cents
byRate[water.tax_rate_bps] = (byRate[water.tax_rate_bps] || 0) + water.price_cents
const tax = Object.entries(byRate).reduce((s, [b, a]) => s + Math.round(a * Number(b) / 10000), 0)
check('税按税率汇总后四舍五入一次 (水 0%)', placed?.tax_cents === tax, { got: placed?.tax_cents, want: tax })
check('同一 client_ref -> 同一单', (await place(items, { ref: myRef }))?.token === placed?.token)

const st = await rpc('online_order_status', { p_token: placed.token })
check('订单页：等待付款，还没有取餐号', st?.status === 'AWAITING_PAYMENT' && st.pickup_code === null, st)
check('订单页：店名、地址、电话', st?.store?.slug === SLUG && !!st.store.address && !!st.store.phone, st?.store)
check('订单页不给顾客看电话号码', st?.customer_phone === undefined)
check('订单页：明细带选项和备注', st?.items?.[0]?.options?.length === 2 && st.items[0].note === '少葱', st?.items?.[0])
check('不存在的订单 -> null', (await rpc('online_order_status', { p_token: 'nope' })) === null)

// ---- what customers can't call
check('付款接口不对顾客开放', !!(await sb.rpc('online_pay_settle', { p_id: placed.id, p_transaction_id: 'x', p_paid_cents: 1, p_auth_code: null, p_card: null })).error)
check('店内接口不对顾客开放', !!(await sb.rpc('online_store_step', { p_id: placed.id, p_step: 'ACCEPT', p_minutes: 10, p_reason: null, p_staff_name: 'x', p_device_sn: null })).error)
check('Cael 接口不对顾客开放', !!(await sb.rpc('cmp_online_orders', { p_org: 'e2e5ca00-0000-4000-8000-000000000001', p_limit: 5 })).error)

// ---- arke-pay
const badTip = await pay({ action: 'open', token: placed.token, tip_cents: 999999, email: 'test@example.com' })
check('小费太大 -> bad_tip', badTip?.error === 'bad_tip', badTip)
const badEmail = await pay({ action: 'open', token: placed.token, tip_cents: 0, email: 'nope' })
check('邮箱不对 -> bad_email', badEmail?.error === 'bad_email', badEmail)
const opened = await pay({ action: 'open', token: placed.token, tip_cents: 150, email: 'test@example.com' })
check('打开 Nuvei 沙盒付款', !!opened?.session_token && opened.env === 'int' && opened.amount_cents === sub + tax && opened.tip_cents === 150, opened)
check('付款金额 = 小计 + 税 + 小费', opened?.amount === ((sub + tax + 150) / 100).toFixed(2), opened?.amount)
// (No 'confirm' here: a session gets one status check from Nuvei, kept for the real payment in the browser.)
check('别的订单令牌查不到这笔付款', (await pay({ action: 'confirm', token: 'nope', id: opened?.id }))?.error === 'not_found')

// ---- phase 2: the store's own delivery (seed-arke.sql: Near K2K/K2L $3.99 min $15, Far K2M $6.99 min $30, Next door K2K1X $1)
const d = menu.store.delivery
check('店铺页带配送信息', d?.minutes === 40 && d.fee_from_cents === 100 && d.min_order_from_cents === 0, d)
const quote = (postal, sub) => rpc('online_delivery_quote', { p_slug: SLUG, p_postal: postal, p_subtotal_cents: sub ?? null })
check('邮编格式不对 -> bad_postal', (await quote('12345'))?.message === 'bad_postal')
check('不在配送区 -> out_of_area', (await quote('M5V 1A1'))?.message === 'out_of_area')
const near = await quote('k2k 2b2', 2000)
check('K2K 2B2 -> Near $3.99，税 13%', near?.success && near.zone === 'Near' && near.fee_cents === 399 && near.fee_tax_cents === 52 && near.postal === 'K2K2B2', near)
check('最长前缀优先：K2K 1X1 -> Next door', (await quote('K2K1X1'))?.zone === 'Next door')
const far = await quote('K2M 1A1', 1000)
check('没到起送额 -> below_minimum（带起送额）', far?.message === 'below_minimum' && far.min_order_cents === 3000, far)
const addr = { line1: '1 Delivery Rd', unit: '1203', city: 'Ottawa', postal: 'K2K 2B2', instructions: 'buzz 1203' }
await expectMsg('配送没填地址 -> address_required', items, 'address_required', { fulfilment: 'OWN_DELIVERY' })
await expectMsg('配送地址不在范围 -> out_of_area', items, 'out_of_area', { fulfilment: 'OWN_DELIVERY', address: { ...addr, postal: 'M5V1A1' } })
await expectMsg('配送没到起送额 -> below_minimum', [{ product_id: WATER, quantity: 1 }], 'below_minimum', { fulfilment: 'OWN_DELIVERY', address: { ...addr, postal: 'K2M1A1' } })
await expectMsg('不认识的取餐方式 -> bad_request', items, 'bad_request', { fulfilment: 'DRONE' })
const dPlaced = await place(items, { fulfilment: 'OWN_DELIVERY', address: addr })
check('配送单：配送费 $3.99', dPlaced?.success && dPlaced.delivery_fee_cents === 399, dPlaced)
check('配送单：税 = 菜的税 + 配送费的税', dPlaced?.tax_cents === tax + 52, { got: dPlaced?.tax_cents, want: tax + 52 })
const dSt = await rpc('online_order_status', { p_token: dPlaced.token })
check('配送单订单页：方式、地址（邮编规范成 K2K 2B2）', dSt?.fulfilment === 'OWN_DELIVERY' && dSt.delivery_address?.postal === 'K2K 2B2'
  && dSt.delivery_address.instructions === 'buzz 1203', dSt?.delivery_address)
check('配送单订单页：合计含配送费', dSt?.total_cents === sub + 399 + tax + 52, dSt?.total_cents)
check('配送单订单页：店里的预计送达时间', dSt?.store?.delivery_minutes === 40)
const dOpened = await pay({ action: 'open', token: dPlaced.token, tip_cents: 0, email: 'test@example.com', lang: 'zh' })
check('配送单付款金额 = 小计 + 配送费 + 税', dOpened?.amount_cents === sub + 399 + tax + 52, dOpened)
check('配送设置接口不对顾客开放', !!(await sb.rpc('cmp_online_delivery_save', { p_org: 'e2e5ca00-0000-4000-8000-000000000001', p_enabled: false, p_minutes: 40, p_tax_bps: 1300, p_zones: [] })).error)

// ---- ordering ahead (docs/migrations/2026-11-18_arke_scheduled_orders.sql)
const slots = menu.store.slots?.PICKUP || []
check('店铺页带可预约时段（自取）', slots.length > 0 && menu.store.schedule_days >= 1, menu.store.schedule_days)
check('时段按 15 分钟、从早到晚', slots.length < 2 || (new Date(slots[1]) - new Date(slots[0]) === 900000 && new Date(slots[0]) % 900000 === 0), slots.slice(0, 2))
check('最早时段不早于现在 + 准备时间', slots.length > 0 && new Date(slots[0]) >= Date.now() + menu.store.pickup_minutes * 60000 - 60000, slots[0])
const coke = { product_id: WATER, option_ids: [], quantity: 1 }
await expectMsg('不在时段里的时间 -> slot_gone', [coke], 'slot_gone', { phone: '613-555-0177', when: new Date(new Date(slots[0]).getTime() + 7 * 60000).toISOString() })
await expectMsg('过去的时间 -> slot_gone', [coke], 'slot_gone', { phone: '613-555-0177', when: new Date(Date.now() - 3600000).toISOString() })
const booked = await place([coke], { phone: '613-555-0177', when: slots[1] ?? slots[0] })
check('预约下单成功', booked?.success === true, booked)
const bookedPage = booked?.token ? await rpc('online_order_status', { p_token: booked.token }) : null
check('订单页带预约时间', bookedPage && new Date(bookedPage.scheduled_for).getTime() === new Date(slots[1] ?? slots[0]).getTime(), bookedPage?.scheduled_for)
check('订单明细带 product_id / option_ids（再来一单要用）', !!bookedPage?.items?.[0]?.product_id && Array.isArray(bookedPage.items[0].option_ids), bookedPage?.items?.[0])
check('预约设置接口不对顾客开放', !!(await sb.rpc('cmp_online_schedule_save', { p_org: 'e2e5ca00-0000-4000-8000-000000000001', p_days: 0 })).error)

console.log(failed ? `\n${failed} 项失败` : '\n全部通过')
console.log(`未付款配送单: ?o=${dPlaced?.token}`)
console.log(`未付款订单: ?o=${placed?.token}  (在浏览器里用 Nuvei 测试卡付款，再到 Ourea 接单)`)
process.exit(failed ? 1 : 0)
