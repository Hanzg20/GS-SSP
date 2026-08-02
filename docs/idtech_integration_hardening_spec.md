# ID TECH 终端集成与健壮性加固规格书 (ID TECH Integration & Hardening Specification)

本规格书定义了 GS-SSP 项目中 ID TECH (NEO2/NEO3) 驱动层的架构设计、状态处理逻辑及深度交易场景验证标准，旨在建立一套工业级稳健的硬件适配体系。

---

## 1. 核心设计目标

### 1.1 全链路交易状态映射
*   **语义化转换**: 将 ID TECH Universal SDK (USDK) 细碎的回调状态转换为 App 统一的业务语义（如：`APPROVED`, `DECLINED`, `CANCELLED`）。
*   **多模式支持**: 
    *   **EMV 芯片**: 完整支持 L2 内核交互。
    *   **MSR 磁条**: 实装 `swipeMSRData` 以支持芯片故障后的降级交易。
    *   **CTLS 非接触**: 优化拍卡感应灵敏度与反馈。

### 1.2 工业级可靠性加固
*   **主动生命周期管理**: 实现 `cancelCurrentTransaction`。确保用户点击“返回”或交易超时后，显式关闭读卡器扫描状态并熄灭背光，防止 USB 句柄锁定。
*   **连接保护机制**: 实时监听 `deviceDisconnected`。硬件意外拔出时，系统必须在 500ms 内拦截支付入口并触发 UI 告警。

### 1.3 深度诊断与运维 (Black Box Ready)
*   **数字化透传**: 支持获取固件版本、Terminal ID 及物理 SN，并展示于技术员面板。
*   **指令回显**: 将读卡器的 LCD 指令（如 "Processing...", "Remove Card"）实时映射到主屏幕 UI。

---

## 2. 硬件抽象层 (HAL) 扩展

为实现多供应商（PAX/ID TECH）兼容，系统采用了工厂模式抽象：

*   **`IHardwareProvider`**: 管理设备初始化与生命周期。
    *   `getFirmwareVersion()`: 提取固件版本用于远程审计。
*   **`IPaymentProvider`**: 统一起售与取消接口。
    *   `cancelCurrentTransaction()`: 核心加固接口。
*   **`IScannerProvider`**: (可选) 驱动硬件扫码模块。

---

## 3. IDT_NEO2 与 IDT_NEO3 演进分析

基于 **Universal SDK (USDK)** 的集成方案，确保了 App 对两代硬件的无缝兼容：

| 特性 | NEO 2 (当前主打) | NEO 3 (下一代) |
| :--- | :--- | :--- |
| **安全认证** | PCI 5.x SRED | **PCI 6.x SRED** |
| **内核策略** | 独立设备内核 | **Common L2 Kernel** (统一内核) |
| **升级路径** | - | 仅需更改设备类型枚举及适配 `.uniFWApp` 固件包 |

---

## 4. 交易场景测试矩阵 (Test Matrix)

| 场景分类 | 测试动作 | 预期业务行为 |
| :--- | :--- | :--- |
| **标准 EMV** | 插入芯片卡并授权 | UI 显示 "Approved"，云端生成 `PAID` 记录。 |
| **磁条降级** | 刷磁条卡 | 触发 `swipeMSRData`，提示 "MSR Read Successfully"。 |
| **中途取消** | 拍卡时点击 "BACK" | 调用 `cancel` 接口，读卡器指示灯立即熄灭。 |
| **交互超时** | 在支付页等待 >30s | 触发 `timeout` 回调，UI 提示超时并复位。 |
| **热插拔故障** | 交易中拔掉读卡器 | 触发 `deviceDisconnected`，App 立即进入锁定状态。 |

---

## 5. 验证与诊断指南

### 5.1 日志审计 (Logcat)
*   **关键字**: `IdTechPayment`, `IdTechHardware`, `EMV Result`.
*   **合规性检查**: 严禁在日志中打印 Track 2 明文，仅允许打印 `Common.parse_MSRData` 掩码后的摘要。

### 5.2 异常流程路径
*   若发生“扣款成功但硬件未响应”，验证 `OfflineQueueManager` 是否正确记录了当前的 `ecr_ref_num` 以便后续冲正或审计。

---

## 6. 支付安全与合规 (PCI-DSS)

> [!CAUTION]
> **数据脱敏要求**:
> 所有 ID TECH 原始 Tags 信息在上传至云端 `app_error_logs` 前，必须经过脱敏处理。禁止在非加密通道传输任何卡片明文信息。

---

## 7. 设计依据与原厂资源 (References & SDK Assets)

本集成方案严格遵循 ID TECH 官方技术标准，并建立在以下原厂资源基础之上：

### 7.1 原厂 SDK 与 资源位置 (Source Assets)

为确保驱动的纯正性与可维护性，项目使用的所有 ID TECH 二进制文件均同步自原厂分发包：

*   **原厂 SDK 本地存储**: `D:\UserData\app-sdk\Id tech-SDK`
    *   该目录包含原始 `.jar` 库、Demo 源码、API HTML 文档及 NEO 命令行手册。
*   **项目集成路径**:
    *   **SDK 库文件**: `app/libs/Universal_SDK_1.00.190_os.jar`
    *   **核心配置文件**: `app/src/main/res/raw/idt_unimagcfg_default.xml` (定义了各型号设备的通信参数与内核映射)。

### 7.2 官方参考文档 (Official References)

1.  **ID TECH 知识库**: [Products - Home](https://idtechproducts.atlassian.net/wiki/spaces/KB/pages/71697919/Products+-+Home)
2.  **Universal SDK 开发者指南**: 参考 `Docs/API/80152505-001_NEO2_Android.pdf`。
3.  **NEO 接口规范**: 遵循 ID TECH NEO 1.01 接口控制文档 (ICD) 标准。

---

## 8. 版本记录
*   **v1.0 (2026-08-01)**: 初始版本。基于 USDK 1.00.190 建立标准。
*   **v1.1 (2026-08-01)**: 增加 NEO3 差异分析与 HAL 生命周期加固规格。
