# Uber Eats 订单接入 Ourea 设计草案

2026-11-17 草案。老板定的方向：先做 Uber Eats 外卖平台对接（顾客在 Uber Eats App 下单，订单进店里的 Ourea）；Uber Direct（Arke 配送单叫 Uber 骑手）以后再做。

## 和 Uber 的约定（官方文档，2026-11-17 查）

- 文档：Marketplace APIs <https://developer.uber.com/docs/eats/introduction>，订单接入 <https://developer.uber.com/docs/eats/guides/order-integration>，Webhook <https://developer.uber.com/docs/eats/guides/webhooks>，沙盒 <https://developer.uber.com/docs/eats/guides/sandbox>。
- 鉴权：OAuth 2.0 client credentials，`POST /oauth/v2/token`（client_id、client_secret、grant_type=client_credentials、scope）。令牌 30 天有效，缓存起来用。
  - 沙盒：令牌域名 `https://sandbox-login.uber.com`，接口域名 `https://test-api.uber.com`。正式：`https://auth.uber.com`、`https://api.uber.com`。**令牌和接口的域名必须同属沙盒或同属正式**，混用会鉴权失败。
- 新订单：Uber 推 `orders.notification` Webhook（`resource_id` = 订单号，`user_id` = Uber 的门店号，`resource_href` = 取订单详情的地址）。必须马上回 200、空 body；然后 **11.5 分钟内**调接单（accept_pos_order）或拒单（deny_pos_order），否则 Uber 自动取消。门店开了电话提醒的话，90 秒没处理 Uber 会打电话。
- Webhook 签名：请求头 `X-Uber-Signature` = 用 client secret 对原始 body 做 HMAC-SHA256，小写十六进制。验不过就不收。
- 重试：我们回 5xx 或超时，Uber 在 10 秒、30 秒、60 秒、120 秒……后重试，最多 7 次。所以同一个订单可能来好几次，入库必须按 Uber 订单号去重。
- 其他事件：`orders.cancel` / `orders.failure`（订单取消）、`orders.release`、`orders.scheduled.notification`（预约单）、`store.status.changed` 等。
- 已知不确定：文档里接口路径有两套写法（`/v1/eats/orders/{id}/accept_pos_order` 和 `/v1/delivery/orders/{id}/accept_pos_order`），出餐（mark ready）的路径也不一致。**拿到沙盒令牌后先逐个试通，再写死。**
- 上线：沙盒权限不等于正式权限。正式要另建 PRODUCTION 应用，通过 Uber 的集成验收（Production Validation）。

## 我们这边的做法

- **订单存哪**：放进 `online.orders`，加一列 `channel`（`ARKE` / `UBER_EATS`）和 Uber 的订单号（同一渠道内唯一）。这样 Ourea 现有的"线上订单"界面、提示音、横幅、厨房单直接能用，不用另做一套。
  - Uber Eats 的钱是 Uber 收的，**我们不记付款**：`online.payments` 里没有它的记录，Arke 的退款流程也不碰它。拒单走 Uber 的 deny 接口。
  - 订单号显示 Uber 的取餐码（display_id），厨房单印"UBER EATS"。
- **门店对应**：新表记"我们的门店 ↔ Uber 门店号"，在 Cael 里由系统管理员填。Webhook 来了按 Uber 门店号找到门店；找不到就记日志、不入库（也不替店家接单）。
- **Webhook**：新 Edge Function `ubereats-webhook`（verify_jwt 关闭，靠签名）：验签 → 按订单号去重 → 回 200 → 取订单详情 → 入库为 NEW。取消事件把订单改成"已取消"并告诉 Ourea。
- **店里操作**：Ourea 点"接单 / 做不了 / 做好了"时，Uber 渠道的单先由服务器调 Uber 的接口（新 Edge Function `ubereats-action`，按设备鉴权，和 `arke-pay-refund` 一样），Uber 确认了再改我们的状态。Uber 没确认就告诉店员，不假装成功。
- **菜单**：第一步不同步菜单，订单里的菜按 Uber 给的名字和选项打厨房单。第二步再做"把 Cael 的菜单推到 Uber"和"售罄同步"。
- **令牌**：服务器缓存，30 天内复用，快过期时换新；只有 Edge Function 能读。

## 分步

1. 连通：密钥存进 Supabase，`ubereats-ping` 拿到沙盒令牌。**2026-11-17 通过**：`eats.order eats.store` 都给了，30 天有效。`GET /v1/eats/stores` 和 `/v1/delivery/stores` 都回 200（`/v2/eats/stores` 404），但门店列表是空的，要等 Uber 给测试门店。`ubereats-ping` 带 `probe` 可以试只读 GET。
2. 收单：门店对应表、Webhook 入库、Ourea 上能看到 Uber 单（先只看不操作）。**2026-11-17 已做、已上线**：迁移 `2026-11-17_uber_eats_orders.sql`（已应用），`ubereats-webhook`（已部署；未签名 / 签名错 401）。模拟单在 Scan E2E 店走通：未关联门店不收、入库为 NEW、重发和同单不同事件只收一次、单号和事件对不上拒收、取不到详情留待 Uber 重试、Ourea 看得到但改不了（`order_on_platform`）、Arke 顾客页查不到 Uber 单、取消改成 CANCELLED。还差：Uber 真推送（等测试门店）。
3. 操作：接单 / 拒单 / 出餐调 Uber 接口；取消事件同步。
4. 菜单和售罄同步。
5. 正式验收：建 PRODUCTION 应用，按 Uber 的验收清单过一遍。

## 要老板办的事

1. 把 Client ID 和 Client Secret 存成 Supabase 密钥（`UBER_CLIENT_ID`、`UBER_CLIENT_SECRET`），不要发在聊天里。
2. 向 Uber 要沙盒测试门店（Integration Tech Support：<http://t.uber.com/integration-support>），拿到测试门店号和下测试单的方法。
3. 在 Developer Dashboard 的应用里 **Webhooks** 页面填 Webhook 地址：`https://ttbtxxpnvkcbyugzdqfw.supabase.co/functions/v1/ubereats-webhook`（等第 2 步做好再填也行）。
