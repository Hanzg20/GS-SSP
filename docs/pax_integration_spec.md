# PAX IM30 终端集成规格书与 API 完整性评估 (PAX Integration Specification)

本规格书定义了 GS-SSP 项目中 PAX IM30 驱动层的架构设计、SDK 依赖及 API 调用规范。

---

## 1. 核心集成方案

### 1.1 架构模式
*   **通讯模式**: 采用 **AIDL (Android Interface Definition Language)**。App 充当 POS 端，通过 AIDL 与终端内置的 `BroadPOS` 支付服务通讯。
*   **半集成模式 (Semi-Integrated)**: 所有的敏感卡片数据和密钥管理均在 PAX 安全模块中处理，App 仅通过 `POSLink` 接口传递金额并接收授权结果。

### 1.2 驱动层级
*   **支付驱动 (`PaxPaymentProvider`)**: 封装 `com.pax.poslink`，处理交易流水。
*   **硬件抽象驱动 (`PaxHardwareProvider`)**: 封装 `com.pax.dal` (NeptuneLite)，管理序列号获取及硬件看门狗。
*   **扫码驱动 (`PaxScannerProvider`)**: 封装物理扫码窗口。

---

## 2. API 完整性评估与风险核查

经对 `D:\UserData\app-sdk\PAX SDK` 目录及现有代码的对比分析，现对 API 的完整性评估如下：

### 2.1 依赖库状态

| 库文件 | 状态 | 评估与影响 |
| :--- | :--- | :--- |
| `PAX_POSLinkAndroid_20260202.aar` | ✅ **已就绪** | 包含核心 AIDL 桥接类 `POSLinkAndroid`。 |
| `POSLink_Core_Android_V2.00.09.jar` | ✅ **已就绪** | 包含协议处理引擎。 |
| `GLComm_V1.12.01.jar` | ✅ **已就绪** | 必需的通讯基础库。 |
| `NeptuneLiteApi.jar` (DAL) | 🔴 **缺失** | **风险**: 目录下未发现该文件。目前 App 依赖项目中的 Stubs 进行编译。虽然在 IM30 真机上能运行（系统自带），但若需使用 SDK 新特性或在无 Stubs 的环境下开发，必须获取此 JAR 包。 |

### 2.2 核心 API 调用确认点

1.  **初始化顺序**: 
    *   必须在 `Application` 或 `MainActivity` 启动时显式调用 `POSLinkAndroid.init(context)`。
2.  **交易码验证**:
    *   `SALE`: 确认使用 `transType = 2`。
    *   `VOID`: 确认使用 `transType = 4`。
    *   `REFUND`: **待确认**。当前代码占位为 `5`，需查阅 `POSLink_Java_Android_V1.17.00_API_Guide.pdf` 第 4.2 章节确认该版本是否变更了指令码。
3.  **AIDL 权限**:
    *   `AndroidManifest.xml` 必须包含 `<queries>` 标签及 `com.pax.us.std.poslink.aidl` 动作声明，否则在高版本 Android (30+) 下无法绑定服务。

---

## 3. 技术风险与规避 (Technical Risks)

*   **Z-Index 竞争**: PAX POSLink 启动交易时可能会弹出自带的 PIN Pad 或签名对话框。需确保这不会与我们现有的“数字座舱”全屏动效产生层级冲突（导致黑屏或闪烁）。
*   **生命周期冲突**: 如果 ID TECH 的 USB 读卡器和 PAX 内部 AIDL 服务同时初始化，需验证 `HardwareFactory` 是否能正确隔离两者的监听器回调，避免状态混乱。

---

## 4. 后续需确定的 API 细节

- [ ] **确认**: `REFUND` 在 V1.17.00 版本的准确 `transType` 编码。
- [ ] **确认**: 是否需要通过 `ManageRequest` 进行签到 (Logon) 操作才能激活 AIDL 连接。
- [ ] **获取**: 缺失的 `NeptuneLiteApi.jar` 原厂包以备生产环境混淆使用。

---

## 5. 版本记录
*   **v1.0 (2026-08-06)**: 初始版本。基于 POSLink Android V1.17.00 与 HAL 架构建立。
