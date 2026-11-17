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

console.log(failed ? `\n${failed} 项失败` : '\n全部通过')
console.log(`未付款订单: ?o=${placed?.token}  (在浏览器里用 Nuvei 测试卡付款，再到 Ourea 接单)`)
process.exit(failed ? 1 : 0)
