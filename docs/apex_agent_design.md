# 小海狸 2.0：会用工具的门店助手（评估与设计）

2026-11-01。目标：小海狸更机灵：能丝滑地算账、查和管订单、解释数据，手账里的事说一句就能办。参考 Meta Muse Spark 的做法（规划 → 调工具 → 汇总），但按我们的约束落地。

## 1. 行业现状（2026 年 11 月）

| 谁 | 做了什么 | 对我们的启发 |
|---|---|---|
| **Meta Muse Spark**（4 月发布，7 月 1.1） | 主打"代理"：先收集上下文、做计划，再把执行分给并行的子代理；工具调用和跨应用协作是强项，长上下文和长任务编程是弱项。API（`api.meta.ai/v1`）只对美国开发者公开预览，$1.25 / $4.25 每百万 token，输出偏啰嗦 | 学它的**行为模式**：计划 → 调工具 → 核对 → 回答。不适合当我们的后端：加拿大用不了，中文能力没公开数据，还在预览 |
| **Square Managerbot**（公测） | 实时盯经营、提前预警（库存跟不上销量、目录缺图）、排班草稿；原则是"**它提议，商家决定**" | 主动提醒 + 每个动作都要人确认，和我们的确认卡思路一致 |
| **Toast IQ** | 先上营销代理，路线图是排班、库存成本、记账各一个代理 | 按业务域拆工具，记账代理还没人真正做好，这是我们的机会（手账 + 小海狸） |
| 共同趋势 | 从"聊天问答"走向"**调工具的代理**"：只读查询随便用，写操作走审批；数字由系统算，模型只负责组织语言 | 小海狸现在还停在"把数据塞给模型让它看"的阶段 |

## 2. 小海狸现在的样子和短板

现状（`supabase/functions/apex-assistant`）：
- 每次提问，把 `apex_store_stats` 一整包快照（今天、本周、本月费用、防损信号，约 2k token）塞进提示词，模型只能"看这一包说话"。
- "记账 / 提醒 / 建送货单"靠正则 `looksLikeCommand` 判断，再单独调一次模型解析成确认卡。只有这 3 种。
- 模型：Groq 免费层 gpt-oss-120b，整个账号每分钟 8k token，大约每分钟 2 到 3 个问题。

短板（老板真会问、现在答不了的）：

| 问法 | 为什么答不了 |
|---|---|
| "上个月第二周卖了多少？比前一周呢？" | 快照里只有今天、本周、本月 |
| "早上 10 点那笔 $23.94 是谁收的？退了没有？" | 没有查单能力 |
| "王老吉还剩多少？这周卖得快不快？" | 快照里没有库存（11-01 才有库存表） |
| "昨天 mark 干了几个小时，小费分多少？" | 没有工时和小费数据 |
| "把开店清单第 2 项勾上""那个订货的待办做完了" | 只有 3 种卡，没有勾清单、完成待办、记笔记、进货 |
| "记一下电费 234.5，再看看这个月水电一共多少" | 一句话只能做一件事，不能先做再查 |
| "为什么今天粗利是负的？" | 只能复述字段，不能顺着数据往下拆 |

## 3. 设计：小海狸 2.0

### 3.1 总体：一个会调工具的代理（不是一包快照）

```
终端（Iris / Ourea）或 Cael
   │  问题 / 语音
   ▼
apex-assistant（Edge Function）
   ├─ 身份和门店：和现在一样，从会话推出 org 和员工，终端发什么都不可信
   ├─ 代理循环（最多 6 轮）：模型 → 调工具 → 拿结果 → 再想 → 回答
   │     ├─ 只读工具：直接执行（SQL，限定本店，金额在 SQL 里算好）
   │     └─ 写工具：不执行，变成"待确认卡片"，交回终端
   ├─ 数字核对：回答里的每个数字必须出现在工具结果里，或者由 calc 算出；
   │            对不上就让模型重写一次，再不对就只给数据表
   └─ 记录：apex_messages（问题、调用了哪些工具、回答），方便排查
   ▼
终端：显示回答 + "依据"小标签（点开跳到对应页面）+ 确认卡片（确认后由终端自己写，
      走现有的 Repository 和离线队列，需要经理 PIN 的照旧要）
```

为什么不照搬 Muse 的多子代理：一个门店助手的问题通常 1 到 3 步就能答完，单个代理加好用的工具就够了；子代理会让成本和延迟翻倍。只有"月底结账报告""多店对比"这种重任务才值得拆子代理（见第 5 节 P5）。

### 3.2 工具清单

只读工具（模型可以随便调，全部在 SQL 里限定本店）：

| 工具 | 回答什么 | 数据来源 |
|---|---|---|
| `sales_summary(from, to, group_by)` | 任意时间段的销售、笔数、客单价；按天 / 小时 / 员工 / 支付方式 / 类别分组 | transactions（PAID、扣掉部分退款） |
| `compare_periods(a, b)` | 两段时间对比，直接给出差额和百分比的现成句子 | 同上 |
| `top_products(from, to, sort, limit)` | 畅销 / 滞销、某个商品的销量趋势 | transaction_items |
| `find_transactions(time, amount, staff, method, last4, status)` | 找那一笔 | transactions |
| `transaction_detail(ref)` | 明细、退款、会员、积分、找零 | transactions + items + refunds |
| `stock_status(query, low_only)` / `stock_moves(product)` | 库存、低库存、每一笔进出 | retail_stock_* |
| `member_lookup(phone or name)` / `top_members` | 会员积分、消费次数 | member.* |
| `staff_hours_tips(day or range)` | 工时、小费分配 | office_tips |
| `expenses_query(from, to, category)` / `profit(from, to)` | 费用、粗利 | expenses、office_day_profit |
| `delivery_orders(status)` | 待送、在路上、带回的原因 | delivery_orders |
| `journal_today()` | 到期的待办、清单完成情况、今天的笔记 | reminders、checklists、notes |
| `calc(expression)` | 需要算的时候（加总、差额、百分比） | 本地算，模型不心算 |

写工具（只产生确认卡，终端确认后才写）：

| 工具 | 手账 / 门店功能 | 终端执行时用 |
|---|---|---|
| `propose_expense` | 记一笔费用（可以一次多笔） | ExpenseRepository（已有） |
| `propose_reminder` / `propose_todo_done` | 设提醒、完成待办 | ReminderRepository（已有） |
| `propose_checklist_tick` | 勾清单项 | ChecklistRepository（已有） |
| `propose_note` | 记笔记 | NoteRepository（已有） |
| `propose_delivery_order` | 建送货单 | DeliveryOrderRepository（已有） |
| `propose_stock_change` | 进货、盘点、调整（必须填原因） | InventoryRepository（11-01 新加） |
| `propose_remember` | 记住门店习惯（"我们周一休息""房租每月 1 号"） | apex_knowledge 里本店的行 |

永远不做：退款、改价、删记录、加员工、动收款设置。问到时告诉他去哪里操作，这条和现在一样。

### 3.3 "丝滑算账"的三条保证

1. **数字只来自工具**：SQL 算好金额（分转元也在服务端做），模型不估算；需要运算就调 `calc`。
2. **回答后核对**：从回答里抽出所有数字，和本轮工具结果加 `calc` 的输出比对。现在识别收据时已经有同样的核对（`parse.ts toReceipt`）。
3. **附上依据**：回答下面显示"依据：11 月 1 日到 7 日销售汇总"这样的标签，点开就是 Records 或报表里对应的那一页。老板能自己核对，不用全靠相信 AI。

### 3.4 主动提醒（学 Managerbot）

在现有的每日简报之外，加几条规则触发的提醒（SQL 定时检查，命中才叫模型写一句话）：
- 低库存、照最近 7 天的销量撑不过 3 天。
- 打烊时还有人没下班打卡。
- 送货单带出去超过 2 小时还没收款。
- 离线刷卡还有没上传成功的。
- 开店清单到 11 点还没做完。
- 退款或作废比平时多（现有的防损规则）。

提醒以卡片形式出现在小海狸页面顶部，点一下就是"去处理"。推送走现有的 `alert-notify`。

### 3.5 模型选择

| 模型 | 价格（每百万 token，输入 / 输出） | 工具调用 | 中文 | 在加拿大能用 | 结论 |
|---|---|---|---|---|---|
| **Claude Haiku 5.5** | $0.10 / $0.50（提示词 10 万 token 以内） | 可靠，支持并行调用和 strict schema | 好 | 能 | **推荐默认** |
| Claude Sonnet 5.5 | $2 / $10 | 更强 | 好 | 能 | 月底报告、复杂分析时才用 |
| gpt-oss-120b（Groq） | 免费层每分钟 8k token；付费约 $0.15 / $0.60 | 支持，但多步时不太稳 | 一般（之前出现过"持平"这类误读） | 能 | 留作备用 |
| Muse Spark 1.1 | $1.25 / $4.25 | 强（MCP Atlas 88.1） | 没有公开数据 | **不能**（只对美国开放预览） | 不用 |

成本估算（Haiku 5.5）：一个问题平均 3 轮调用，输入约 8k token（系统提示和工具定义会被缓存）、输出约 600 token，大约 **$0.001 一个问题**。一家店一天问 100 次，约 $0.10 一天，**每月约 $3 一家店**。缓存后还会更低。代码里已经能切 `APEX_PROVIDER=anthropic`，只是还没设 key。

实现要点：Edge Function 改用官方 `@anthropic-ai/sdk`（Deno 支持 `npm:` 导入），不再手写 fetch。工具定义加 `strict: true`。Haiku 5.5 的 effort 用 `low`（闲聊、简单查询）或 `medium`（分析）。系统提示词和工具定义放在缓存断点前面，门店数据和问题放在后面。

### 3.6 权限和安全

- 经理才能用（现状）。以后可以开一个收银员模式：只给查单、查库存、记笔记，不能查利润和工时。
- 商品名、笔记、顾客备注里的文字只当数据，不当指令（防提示词注入）。工具结果用固定格式包起来交给模型。
- 每一张确认卡都在终端由真人点确认；需要经理 PIN 的操作（盘点调整、改小费规则）照旧要 PIN。
- 每家店每天的问题上限保留（`APEX_DAILY_LIMIT`）。

## 4. 怎么判断"更机灵了"

- **评测集**：现有 83 个真实提问，加上 60 个按第 2 节的表格写的问题（中英文各半）。每题标出应该调哪些工具、关键数字是多少。
- **自动打分**：数字对不对（拿 SQL 的结果直接比）、有没有编造、该弹的卡有没有弹、该拒绝的有没有拒绝。
- **上线门槛**：数字准确率 ≥ 98%，编造为 0，平均回答时间 ≤ 4 秒。

## 5. 分阶段

| 阶段 | 内容 | 依赖 |
|---|---|---|
| **P1 会查 + 形象** | 代理循环；只读工具（销售、对比、查单、库存、会员、工时小费、费用粗利、送货）；`calc`；数字核对；依据标签。小海狸动态形象（简单版，用现有的 apex_beaver.png 加代码动画）：待机、在听、在想、办好了四种状态；Ourea 放在侧栏底部，Iris 只给经理；付款界面隐藏，顾客屏不放，设置里可以关 | 先在 Groq 免费层开发；上线前用 Haiku 5.5 跑评测，有正式用户时切到 Haiku（老板 11-01 决定） |
| **P2 会办** | 写工具全部做成确认卡（手账五项 + 进货盘点 + 送货）；一句话里有多件事时出多张卡；"记住"门店习惯 | P1 |
| **P3 会提醒** | 第 3.4 节的规则提醒，推送和"去处理"按钮；小海狸加"有事"状态（举牌子显示件数） | P1 |
| **P4 Cael 和老板视角** | Cael 里也能用同一套查询工具（不弹卡）；多店老板问"哪家店这周最好" | P1 |
| **P5 重任务** | 月底结账报告（销售、费用、粗利、库存变动、税），这时才用子代理并行查、Sonnet 汇总 | P1–P4 |

## 6. 需要老板决定

1. ~~是否现在就用付费的 Anthropic key~~ 已定（11-01）：开发用 Groq 免费层，代码两家都能跑；上线前用 Haiku 跑一次评测；有正式用户后切到 Haiku 5.5。
2. 收银员要不要也能用（受限模式）。
3. 主动提醒推给谁：只推经理，还是也推老板的手机。

## 参考
- Meta Muse Spark：[PureAI](https://pureai.com/articles/2026/04/09/meta-launches-muse-spark.aspx)、[Engadget](https://engadget.com/ai/metas-muse-spark-model-brings-reasoning-capabilities-to-the-meta-ai-app-161456684.html)、[1.1（DataNorth）](https://datanorth.ai/news/meta-releases-muse-spark-1-1-agentic-ai-model)、[价格与弱点（eesel）](https://www.eesel.ai/blog/meta-muse-spark-11)
- Square Managerbot：[PYMNTS](https://www.pymnts.com/artificial-intelligence-2/2026/new-square-ai-agent-automates-daily-tasks-for-main-street-sellers)、[QSR](https://www.qsrmagazine.com/news/square-debuts-managerbot-to-automate-small-business-operations/)
- Toast：[NRN](https://www.nrn.com/restaurant-technology/toast-unveils-an-array-of-new-ai-agents-for-restaurants)、[eMarketer](https://www.emarketer.com/content/toast-q4-2025-earnings-reveal-all-in-ai-strategy)
- Groq gpt-oss 价格：[morphllm](https://www.morphllm.com/groq-pricing)
