# Uber Eats / DoorDash 接单：评估（2026-11-02）

目标：外卖平台的订单直接进 Ourea（和桌台、扫码点单一样出厨房单、进销售和库存），菜单和沽清从我们这边同步过去。

## 现实情况

| | Uber Eats | DoorDash |
|---|---|---|
| 接口 | Marketplace APIs：门店、菜单、订单（webhook 推单，接单/拒单/出餐状态） | Marketplace 集成：菜单必须来自 POS/中间件，订单推给 POS |
| 能不能直接用 | 不能。要先签 NDA 和 API 许可协议，由 Uber Eats 的合作经理批准，开发完还要过集成评审 | 不能。要申请成为技术合作伙伴；2026 年标准更严，只上线达到可靠性要求的集成 |
| 上线门槛 | 订单注入成功率 ≥ 99%，低于就可能收回接口权限 | 规模、稳定性、对顾客影响的审核 |
| 加拿大 | 支持（Toast、Clover 已经这样接入） | 支持（加拿大商家帮助页有 POS 集成流程） |

结论：技术上不难（和 Ourea Scan 扫码点单的"订单进来 → 员工接单 → 进下一轮/出厨房单"是同一条路），**卡在合作资格**。没有平台批准，写了也用不了。

## 两条路

1. **自己成为合作伙伴**（长期，推荐等有 10 家以上餐厅客户再做）：申请 Uber Eats 和 DoorDash 的开发者/合作伙伴资格 → 拿到沙盒 → 开发（约 3-4 周：webhook 接单、菜单同步、沽清、状态回传、对账）→ 平台评审。
2. **先接中间件**（短期）：Otter、Deliverect、Chowly 这类聚合商已经接好了各平台，我们只接一家中间件的 API，就能收 Uber Eats / DoorDash / SkipTheDishes（加拿大很重要）的单。代价是商家要另付中间件月费。

## 需要老板做的

- 决定走哪条路。
- 走第 1 条：用公司名义在 developer.uber.com 和 developer.doordash.com 申请，并联系 Uber Eats 合作经理（我们这边只能等批准）。
- 走第 2 条：选一家中间件，申请它的合作伙伴 API。

## 我们这边已经具备的

- Ourea 扫码点单的"待接单 → 接单 → 变成桌台的下一轮 / 出厨房单"流程（`dine.scan_orders`），外卖订单可以复用。
- 商品目录和选项（`products.attributes.modifier_groups`），可以作为菜单同步的来源。
- 销售、库存、积分都按交易记录自动走，外卖单进来后不用另外处理。

来源：[Uber Eats Marketplace APIs](https://developer.uber.com/docs/eats/introduction)、[Uber Eats 入门](https://developer.uber.com/docs/eats/guides/getting-started)、[Uber Eats 质量标准](https://developer.uber.com/docs/eats/quality-and-performance)、[DoorDash Marketplace](https://developer.doordash.com/docs/marketplace/overview/about_marketplace)、[DoorDash POS 集成（加拿大）](https://help.doordash.com/en-ca/merchants/article/intergrate-pos-with-doordash)、[Uber Eats 与 Toast/Clover](https://www.restaurantdive.com/news/Uber-Eats-integration-Toast-Clover/632672/)
