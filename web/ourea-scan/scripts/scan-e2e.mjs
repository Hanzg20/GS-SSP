/**
 * Ourea Scan server-side checks against the E2E store (tools/scan-e2e/seed.sql),
 * through the same anon RPCs the customer page uses. Run tools/scan-e2e/reset.sql
 * first: the abuse limits count each table's last 10 minutes.
 *
 *   cd web/ourea-scan && node scripts/scan-e2e.mjs
 *
 * Leaves PENDING orders on T1 (happy path, for the terminal to accept) and T4
 * (limit test); reset.sql removes them.
 */
import { createClient } from '@supabase/supabase-js'

const SUPABASE_URL = process.env.VITE_SUPABASE_URL || 'https://ttbtxxpnvkcbyugzdqfw.supabase.co'
const SUPABASE_KEY = process.env.VITE_SUPABASE_KEY || 'sb_publishable_g4TGLHlVn3LfafRZFOAjnA_x9M0xyBi'
const sb = createClient(SUPABASE_URL, SUPABASE_KEY, { auth: { persistSession: false } })

const T = (n) => 'e2e' + String(n).padStart(29, '0')
const P = (n) => 'e2e5ca00-0000-4000-8001-' + String(n).padStart(12, '0')
const NOODLE = P(1), ZHAJIANG = P(2), SOLD_OUT = P(5), WATER = P(7), RETIRED = P(9)

let failed = 0
const check = (name, ok, detail) => {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${ok || detail === undefined ? '' : '  -> ' + JSON.stringify(detail)}`)
  if (!ok) failed++
}
const ref = () => 'e2e-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
async function rpc(fn, args) {
  const { data, error } = await sb.rpc(fn, args)
  if (error) throw new Error(`${fn}: ${error.message}`)
  return data
}
const submit = (table, items, clientRef = ref(), note = null) =>
  rpc('scan_submit_order', { p_token: T(table), p_items: items, p_note: note, p_client_ref: clientRef })
const expectMsg = async (name, table, items, msg, clientRef) => {
  const r = await submit(table, items, clientRef)
  check(name, r?.success === false && r.message === msg, r)
}

// ---- menu
const menu = await rpc('scan_menu', { p_token: T(1) })
check('菜单加载 (T1)', menu?.table?.name === 'T1' && Array.isArray(menu.products), menu)
const ids = new Set(menu.products.map((p) => p.id))
check('菜单含 8 道在售菜', menu.products.length === 8, menu.products.length)
check('下架菜不出现', !ids.has(RETIRED))
check('售罄菜标记 sold_out', menu.products.find((p) => p.id === SOLD_OUT)?.sold_out === true)
check('加料组随菜返回', menu.products.find((p) => p.id === NOODLE)?.modifier_groups?.length === 2)
check('0% 税率菜', menu.products.find((p) => p.id === WATER)?.tax_rate_bps === 0)
check('无效桌码 -> 无菜单', (await rpc('scan_menu', { p_token: 'nope' })) === null)
check('停用桌台 T9 -> 无菜单', (await rpc('scan_menu', { p_token: T(9) })) === null)

// ---- rejected requests (T3; none of these create an order)
await expectMsg('无效桌码下单 -> unknown_table', 'x', [{ product_id: ZHAJIANG, quantity: 1 }], 'unknown_table')
check('停用桌台下单 -> unknown_table', (await submit(9, [{ product_id: ZHAJIANG, quantity: 1 }])).message === 'unknown_table')
await expectMsg('空 client_ref -> bad_request', 3, [{ product_id: ZHAJIANG, quantity: 1 }], 'bad_request', '')
await expectMsg('空购物车 -> items_required', 3, [], 'items_required')
await expectMsg('数量 0 -> bad_quantity', 3, [{ product_id: ZHAJIANG, quantity: 0 }], 'bad_quantity')
await expectMsg('数量 21 -> bad_quantity', 3, [{ product_id: ZHAJIANG, quantity: 21 }], 'bad_quantity')
await expectMsg('必选辣度没选 -> bad_option', 3, [{ product_id: NOODLE, quantity: 1, option_ids: ['egg'] }], 'bad_option')
await expectMsg('辣度选两个 -> bad_option', 3, [{ product_id: NOODLE, quantity: 1, option_ids: ['spice0', 'spice1'] }], 'bad_option')
await expectMsg('加料超过 2 个 -> bad_option', 3, [{ product_id: NOODLE, quantity: 1, option_ids: ['spice1', 'egg', 'beef', 'veg'] }], 'bad_option')
await expectMsg('不属于这道菜的选项 -> bad_option', 3, [{ product_id: ZHAJIANG, quantity: 1, option_ids: ['egg'] }], 'bad_option')
await expectMsg('售罄菜 -> sold_out', 3, [{ product_id: SOLD_OUT, quantity: 1 }], 'sold_out')
await expectMsg('下架菜 -> item_unavailable', 3, [{ product_id: RETIRED, quantity: 1 }], 'item_unavailable')
await expectMsg('不存在的菜 -> item_unavailable', 3, [{ product_id: '00000000-0000-4000-8000-000000000000', quantity: 1 }], 'item_unavailable')

// ---- happy path (T1): server prices it, not the client
// noodle 14.95 + egg 1.50 + beef 3.00 = 19.45 x2 = 38.90 @13%; water 2.00 x3 = 6.00 @0%
const items = [
  { product_id: NOODLE, quantity: 2, option_ids: ['spice2', 'egg', 'beef'], note: '少放葱 less scallion', price_cents: 1 },
  { product_id: WATER, quantity: 3 },
]
const clientRef = ref()
const ok = await submit(1, items, clientRef, 'E2E 主流程 — 请在终端接单')
check('正常下单成功', ok?.success === true && !!ok.id, ok)
check('小计由服务器算 (4490)', ok?.subtotal_cents === 3890 + 600, ok?.subtotal_cents)
check('税只算 13% 部分 (506)', ok?.tax_cents === Math.round(3890 * 0.13), ok?.tax_cents)
const dup = await submit(1, items, clientRef)
check('重复提交同一 client_ref -> 同一单', dup?.success === true && dup.duplicate === true && dup.id === ok.id, dup)
const status = await rpc('scan_orders', { p_token: T(1), p_ids: [ok.id] })
check('顾客查单 -> PENDING', status?.[0]?.status === 'PENDING', status)
check('选项名称写进订单', JSON.stringify(status?.[0]?.items?.[0]?.options) === JSON.stringify(['中辣 Medium', '加蛋 Egg', '加牛肉 Extra beef']), status?.[0]?.items?.[0])
check('别的桌码查不到这单', (await rpc('scan_orders', { p_token: T(2), p_ids: [ok.id] })).length === 0)

// ---- abuse limit (T4): 3 pending max
for (let i = 1; i <= 3; i++) {
  const r = await submit(4, [{ product_id: ZHAJIANG, quantity: 1 }])
  check(`T4 第 ${i} 单`, r?.success === true, r)
}
await expectMsg('T4 第 4 单 -> too_many_orders', 4, [{ product_id: ZHAJIANG, quantity: 1 }], 'too_many_orders')

console.log(failed ? `\n${failed} 项失败` : '\n全部通过')
console.log(`T1 待接单: ${ok?.id}  (终端 4 秒内应出现横幅)`)
process.exit(failed ? 1 : 0)
