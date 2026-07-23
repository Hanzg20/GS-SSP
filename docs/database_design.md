# GS-SSP 数据库设计规格书 v2.0 (Platform Evolution)

本文档定义了 GS-SSP 平台级后端数据模型。新版本引入了多租户隔离、通用产品模型及工业级诊断审计体系。

> **[v2.1 增补，v2.2 起已合并入 Full Schema]** 本轮加固新增/变更了三张表，建表脚本现已统一在 `docs/supabase_full_schema.sql`（需人工执行，本文档未自动同步到线上库；此前拆分在 `supabase/migrations/` 下的三个文件已合并删除，避免两份 schema 各自漂移）。详细动机见 `docs/system_architecture.md` 第 10 章。
> *   `vip_cards`：`anon`/`authenticated` 的 `INSERT`/`UPDATE`/`DELETE` 已被收回，余额扣减唯一入口是 `deduct_vip_balance()` RPC（`0001_vip_deduct_balance_rpc.sql`）。
> *   `qr_payment_sessions`（新增）：扫码支付会话表，见 `0002_qr_payment_sessions.sql`，`status` 只能由 service role 写 `PAID`。
> *   `devices.is_active`：迁移脚本 `0003_devices_is_active.sql` 确保列存在，供 `DeviceAccessManager` 读取作为远程锁定网关。

## 1. 实体关系图 (ER Diagram)

```mermaid
erDiagram
    organizations ||--o{ locations : "manages"
    organizations ||--o{ products : "owns"
    locations ||--o{ devices : "houses"
    devices ||--o{ heartbeats : "reports"
    devices ||--o{ app_error_logs : "records"
    devices ||--o{ maintenance_records : "tracks"
    devices ||--o| device_shadows : "state"
    
    organizations {
        uuid id PK
        string name
        string tier "FREE/PRO/ENT"
    }

    locations {
        uuid id PK
        uuid org_id FK
        string name
        string timezone
    }

    devices {
        string sn PK "物理序列号"
        uuid loc_id FK
        string vertical_type "WASH/LAUNDRY/EV/VEND"
        string status "ONLINE/OFFLINE/FAULT"
        string app_version
        boolean is_active
        timestamptz last_seen
    }

    products {
        uuid id PK
        uuid org_id FK
        string vertical_type
        string name
        int price_cents
        jsonb attributes "行业特有参数: {pulse: 12, mode: 'COM'}"
        boolean is_active
    }

    app_error_logs {
        bigserial id PK
        string device_sn FK
        string severity "ERROR/CRITICAL"
        string error_code
        text stack_trace
        jsonb context "状态快照: {mem_free: 100, rssi: -60}"
        timestamptz created_at
    }

    maintenance_records {
        bigserial id PK
        string device_sn FK
        string action "RELAY_TEST/MODE_SWITCH/REBOOT"
        jsonb payload "动作细节"
        timestamptz created_at
    }

    device_shadows {
        string device_sn PK FK
        jsonb desired "预期配置"
        jsonb reported "实际配置"
        int version
    }
```

---

## 2. 数据表详解 (Key Table Extensions)

### 2.1 products (通用产品目录)
支持跨行业的计费单元定义。

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| `org_id` | UUID | 所属租户。 |
| `vertical_type`| TEXT | 行业类型（WASH, LAUNDRY 等）。终端以此切换 UI 模板。 |
| `attributes` | JSONB | **关键扩展字段**。存储指令码、脉冲数或货道信息。 |

### 2.2 app_error_logs (异常审计)
用于工业级远程诊断。

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| `error_code` | TEXT | 业务自定义错误码（如 `SERIAL_FAIL`, `DB_TIMEOUT`）。 |
| `context` | JSONB | 发生错误时的设备环境变量快照。 |

### 2.3 maintenance_records (维护轨迹)
记录技术员在终端上的敏感操作。

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| `action` | TEXT | 操作类型名。 |
| `payload` | JSONB | 记录手动修改后的参数值或测试指令。 |

### 2.4 device_shadows (设备影子 / 数字孪生)
用于远程状态同步与配置分发。

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| `device_sn` | TEXT | **主键**。物理序列号。 |
| `desired` | JSONB | 云端期望状态（如 `{"brightness": 255}`）。 |
| `reported` | JSONB | 终端实际状态（包含最后同步时间）。 |
| `version` | INTEGER | 逻辑版本号，用于冲突检测。 |

---

## 3. 多租户隔离与安全 (Security & RLS)

### 3.1 RLS 策略策略
系统采用 **组织级隔离 (Org-Level Isolation)**：
*   **devices/products**: `SELECT` 权限受限，仅允许携带合法 `org_id` 令牌的终端访问。
*   **logs/heartbeats**: `INSERT` 权限对所有注册设备开放，`SELECT` 仅对管理员开放。

### 3.2 影子设备同步
采用 **版本化增量同步 (Versioned Sync)**。终端启动时检查 `device_shadows.version`，若本地版本落后，则拉取 `desired` 状态并应用。
