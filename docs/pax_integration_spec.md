# PAX IM30 终端集成规格书与 API 完整性评估 (PAX Integration Specification)

本规格书定义了 GS-SSP 项目中 PAX IM30 驱动层的架构设计、SDK 依赖及 API 调用规范。

**v1.1 更新说明**：v1.0 提交后对已合并的 `payment/hardware/pax/*` 代码做了走查，并对 `D:\UserData\app-sdk\PAX SDK` 做了完整性核对。发现若干阻断性实现缺口和一份此前未纳入评估的官方 SDK 包，详见 §3、§2.1、§5。

**v1.3 更新说明**：§3 里不依赖 NeptuneLite/DAL 的代码问题（3.1-3.4、3.6、3.7）已经修完并跑通编译。过程中还发现并修复了一个**比这些都更基础的问题**：`app/libs/` 里同时放了 PAX AAR 和它已经自带的几个 jar（GLComm/POSLink_Core/PaxLog），导致 `./gradlew assembleDebug` **在这次修复之前就已经打不出安装包**——不管改不改 PAX 代码，这个问题本身就挡住了所有人的开发，详见 §2.1、§3.9。**在 NeptuneLite/DAL 到位、真机验证完成前，仍不应该把 `hardwareVendor` 切换到 `"PAX"` 用于生产**，但日常开发（含卡支付主链路）现在可以正常进行。

---

## 1. 核心集成方案

### 1.1 架构模式
*   **通讯模式**: 采用 **AIDL (Android Interface Definition Language)**。App 充当 POS 端，通过 AIDL 与终端内置的 `BroadPOS` 支付服务通讯。
*   **半集成模式 (Semi-Integrated)**: 所有的敏感卡片数据和密钥管理均在 PAX 安全模块中处理，App 仅通过 `POSLink` 接口传递金额并接收授权结果。

### 1.2 驱动层级
*   **支付驱动 (`PaxPaymentProvider`)**: 封装 `com.pax.poslink`，处理交易流水。
*   **硬件抽象驱动 (`PaxHardwareProvider`)**: 封装 `com.pax.dal` (NeptuneLite)，管理序列号获取及硬件看门狗。
*   **扫码驱动 (`PaxScannerProvider`)**: 封装物理扫码窗口。

### 1.3 与既有 PAX 封装的关系（⚠️ 待解决的架构冲突）
项目里在这次 `payment/hardware/pax/*` 落地之前，已经存在一套独立的 PAX 封装并且仍在被 `MainActivity` 直接使用：

*   `payment/PaxScannerManager.kt` —— 负责条码扫描 **以及** NFC/PICC 卡检测（`MainActivity.kt:354` 扫码、`:630` VIP 拍卡、`:1298` NFC 诊断按钮），内置 `checkPaxAvailability()` 的 `Class.forName` mock-fallback 模式（CLAUDE.md 里描述的"保留 mock-fallback pattern"就是指这个）。
*   `payment/PaymentService.kt` —— 老的 POSLink 封装。
*   `MainActivity.kt:1316` 亮度调节代码 —— 又是第三处，直接 `NeptuneLiteUser.getInstance().getDal(this)`，绕开上面两层。

新的 `HardwareFactory` / `IHardwareProvider` 体系（本文档主体描述的对象）是**第四条**访问 PAX DAL/POSLink 的路径，目前只在卡支付主流程（`MainActivity.kt:528,555,751`）接入，扫码/NFC/亮度仍走旧路径。四条路径并存意味着：
1. DAL 可能被重复初始化/持有多份句柄；
2. 新路径没有复刻旧路径的 mock-fallback（见 §3.5），行为不一致；
3. `docs/system_architecture.md` v2.14 "逻辑层与厂商 SDK 100% 隔离" 的表述目前不成立，需要修正或者补一个后续版本把旧路径迁移/收敛进新 HAL。

**建议**：在正式打开 PAX 开关之前，先把 `PaxScannerManager`/`PaymentService`/亮度调节的直接 DAL 调用迁移到 `PaxHardwareProvider`/`PaxScannerProvider` 之上（复用同一个 `dal` 实例），而不是长期维持四条平行路径。

---

## 2. API 完整性评估与风险核查

经对 `D:\UserData\app-sdk\PAX SDK` 目录、`app/libs/` 实际文件、以及现有代码的比对分析（v1.1 补充了逐字节校验和全盘搜索），现对 API 的完整性评估如下：

### 2.1 依赖库状态

| 库文件 | 状态 | 评估与影响 |
| :--- | :--- | :--- |
| `PAX_POSLinkAndroid_20260202.aar` | ✅ **已就绪** | 包含核心 AIDL 桥接类 `POSLinkAndroid`，且**自带** GLComm、POSLink_Core、PaxLog（SDK 自己的 `use_aar/README.md`："The AAR integrates GLComm and GLExtPrinter"）。`app/libs/` 现在只保留这一个 PAX 文件。 |
| `POSLink_Core_Android_V2.00.09.jar` / `GLComm_V1.12.01.jar` / `PaxLog_1.0.11.jar` | 🟢 **v1.3 已从 `app/libs/` 移除** | 之前和 AAR 一起放在 `app/libs/` 里，但 AAR 已经自带这三个库的编译产物，同时存在会导致 dex 阶段 `Duplicate class` 冲突（`com.pax.log.*`、`com.pax.posproto.*`、`com.pax.serialport.*` 等大量类），使 **`./gradlew assembleDebug` 直接编译失败，打不出 APK**（与选哪个 `hardwareVendor` 无关，任何配置都会炸）。已移出到仓库外的 `_removed_libs_backup/`（未删除，SDK 里原文件还在 `D:\UserData\app-sdk\PAX SDK\...\use_jar\`，需要的话可以按 SDK 自己的"方式二：单独 JAR"重新配，但不要和 AAR 混用），详见 §3.9。 |
| `NeptuneLiteApi.jar` (DAL) | 🔴 **确认缺失，非本地快照问题** | 对 `D:\UserData` **全盘**搜索 `neptune`/`dal` 关键字**零匹配**——不是这次没放，是这个 SDK 包从未被获取过。`PaxHardwareProvider`/`PaxScannerProvider` 当前完全靠仓库本地 stub (`app/src/main/java/com/pax/**`) 编译通过，`IDAL`/`IScanner`/`IPicc` 等签名有没有对上厂商真实实现，只有装到 IM30 真机才能验证。**这是当前唯一的阻断性缺件**，需要向 PAX 渠道/经销商单独索取，公开 GitHub（`paxstore-3rd-app-android-sdk`）已确认不含此文件（见 §5 附注）。 |
| `POSLink_Semi_Integration_Java_Android_V2.03.00_20260519` | 🟡 **新发现，未纳入过评估** | 与当前使用的 V1.17.00 包平级存在，日期更新（2026-05-19），命名直接是 "Semi_Integration"。**但其 API 是 `com.pax.poslink2.pigeon.*`（Pigeon 生成代码 + Flutter 插件注册类），与当前代码使用的 `com.pax.poslink.PosLink`/`PaymentRequest`（V1 风格）包名和调用方式完全不同，不能直接替换**。价值在于它自带 `sample/POSLink_SemiIntegration_Demo_Source_Code_20260519.zip` 官方 Demo 源码和 `doc/doc_java/semiintegration/` javadoc，可用来交叉验证 §5 里"待确认"的业务语义（Logon 是否必需、REFUND 编码），因为这些是跨代际通用的交易概念。 |

### 2.2 核心 API 调用确认点

1.  **初始化顺序**:
    *   必须在 `Application` 或 `MainActivity` 启动时显式调用 `POSLinkAndroid.init(context)`。✅ 已在 `PaxHardwareProvider.init()` 中实现。
2.  **交易码验证**:
    *   `SALE`: 确认使用 `transType = 2`。✅ 已实现（`PaxPaymentProvider.kt:54`）。
    *   `VOID`: 确认使用 `transType = 4`。✅ 已实现（`PaxPaymentProvider.kt:90`）。
    *   `REFUND`: 🔴 **确认为 bug（v1.2 核实）**。用 `pdftotext` 提取 `POSLink_Java_Android_V1.17.00_API_Guide.pdf` 附录 6.1 PaymentTransType 后确认：官方枚举**根本没有 "REFUND" 这个值**（`UNKNOWN=0 AUTH=1 SALE=2 RETURN=3 VOID=4 POSTAUTH=5 FORCEAUTH=6 ...`）。`PaxPaymentProvider.kt:118` 写的 `transType = 5` 实际对应 **`POSTAUTH`**（完成预授权），不是退款；真正的退款语义是 **`RETURN = 3`**。修复方式是把 `transType = 5` 改成 `transType = 3`，见 §6。
3.  **AIDL 权限**:
    *   `AndroidManifest.xml` 必须包含 `<queries>` 标签及 `com.pax.us.std.poslink.aidl` 动作声明，否则在高版本 Android (30+) 下无法绑定服务。🟢 **v1.2 已修复**：`app/build.gradle:18` `targetSdk 34` 满足触发条件，已按 `AIDL_Guide.pdf` 原文把 `<queries>` 块加进 `AndroidManifest.xml`。
4.  **Logon/签到**: 🟢 **v1.2 基本解决**。`AIDL_Guide.pdf` "Simple Code Guidance" 原文只要求 `CommSetting#setType(AIDL)` 后直接 `ProcessTrans()`，未提及任何签到步骤，与当前代码调用方式一致。不是逐字的"官方确认不需要"，但已有官方示例代码背书，风险等级从"未知"降为"低"。

---

## 3. 代码走查发现的问题 (v1.1 新增，v1.3 更新修复状态)

对已合并的 `payment/hardware/pax/PaxHardwareProvider.kt`、`PaxPaymentProvider.kt`、`PaxScannerProvider.kt`、`HardwareFactory.kt` 做走查后确认的问题，按严重程度排列：

### 3.1 ✅ 已修复（原🔴阻断性）：PAX 卡支付流程会永久卡死
`PaxPaymentProvider.startCardDetection()`（`PaxPaymentProvider.kt:141-145`）原来只打日志、**从不调用 callback**，而 `IPaymentProvider.kt:47-56` 的接口文档明确要求没有独立 detection 能力的厂商应立即 `onSuccess` 回调。已改成 `callback.onSuccess("", "")` 立即回调，与 `IdTechPaymentProvider.startCardDetection` 用的是同一模式。

### 3.2 ✅ 已修复（原🔴阻断性）：卡住后无法取消
`PaxPaymentProvider.cancelCurrentTransaction()` 原来只有注释、无实现。用 `pdftotext` 核实 `API_Guide.pdf` 后确认 `PosLink` 有真实的 `CancelTrans()` 方法（"used to cancel transaction while POSLink is processing... only effective before the transaction is [sent to the host]"）。现在 `PaxPaymentProvider` 用 `activePosLink` 字段跟踪当前在途的 `PosLink` 实例（在 `startSale`/`voidTransaction`/`refundTransaction` 里设置，`finally` 里清理），`cancelCurrentTransaction()` 调用它的 `CancelTrans()`。本地 stub（`app/src/main/java/com/pax/poslink/PosLink.java`）也补上了这个方法签名。

### 3.3 ✅ 已修复（原🟠）：`HardwareFactory` 单例缓存不区分厂商
`hardwareProvider` 从单个可空变量改成 `MutableMap<String, IHardwareProvider>`，按 `vendor.uppercase()` 做 key。

### 3.4 ✅ 已修复（原🟠）：`PaxPaymentProvider` 每次都是新实例，配置不持久
`PaxHardwareProvider` 现在和 `IdTechHardwareProvider` 一样，自己持有并懒加载单例的 `PaxPaymentProvider`/`PaxScannerProvider`（`getPaymentProvider()`/`getScannerProvider()`），`HardwareFactory` 改为委托给它们而不是每次 `new`。`updateConfig()` 的状态现在能在多次调用间存活。

### 3.5 🟡 部分解决：新 PAX 封装的 mock-fallback
CLAUDE.md 明确要求"为任何新硬件集成保留 mock-fallback pattern"（参照 `PaxScannerManager.checkPaxAvailability()`）。**已解决**：`PaxScannerProvider`（DAL/scanner 不可用时，`startScan()` 会走 `startMockScan()`，4 秒后模拟返回一个扫码结果，和 `PaxScannerManager` 的 mock 行为一致）。**未处理**：`PaxPaymentProvider` 本身没有加 mock 分支——但这是刻意的，因为 `MainActivity` 在 `isSimulationMode == true` 时根本不会调用到 `HardwareFactory.getPaymentProvider().startSale()`（见 `MainActivity.kt:747` 的 `if (isSimulationMode) { ... } else { 真实 provider }`），所以支付这条线的 mock 已经在更上层处理了，不需要在 provider 内部重复一份。

### 3.6 ✅ 已修复（原🟡）：`PaxScannerProvider` 扫描器句柄泄漏
`s.open()` 成功后 `s.startScan(...)` 抛异常的 catch 分支现在也会尝试 `s.close()`。

### 3.7 ✅ 已修复（原🟡）：扫描回调线程未切主线程
`PaxScannerProvider` 的 `onSuccess`/`onFail` 现在通过 `Handler(Looper.getMainLooper()).post {}` 把回调切回主线程，和 `PaxPaymentProvider` 的 `withContext(Dispatchers.Main)` 保持一致。

### 3.8 ⚪ 未处理：`hardwareVendor` 硬编码，未接入云配置
`MainActivity.kt:56`：`private var hardwareVendor: String = "IDTECH"` 仍是硬编码常量，没有接入 `ConfigManager`/云端配置。这次没动它——涉及 `ConfigManager`/`AppConfig` schema 改动，范围比其他几项大，留到专门的一次改动里做，不跟这批小修复混在一起。也与 `system_architecture.md` v2.14 "支持通过配置动态路由" 的表述不符（该文档措辞需要同步修正，见 §6）。

### 3.9 🔴 已修复（v1.3 新发现的阻断性问题，比 3.1-3.8 都更基础）：`assembleDebug` 打不出 APK
`app/libs/` 里同时放着 `PAX_POSLinkAndroid_20260202.aar` 和它自己已经包含的 `POSLink_Core_Android_V2.00.09.jar`、`GLComm_V1.12.01.jar`、`PaxLog_1.0.11.jar`——PAX SDK 自己的 `use_aar/README.md` 写得很清楚："The AAR integrates GLComm and GLExtPrinter"，这几种引入方式（AAR 一个包 vs. 手动拼各个 JAR）是"二选一"，混用会导致 dex 合并阶段报几十个 `Duplicate class`（`com.pax.log.*`、`com.pax.posproto.*`、`com.pax.serialport.*` 等），**`./gradlew assembleDebug` 直接失败，打不出任何 APK**——这个跟选哪个 `hardwareVendor` 完全无关，`compileDebugKotlin`（Kotlin/Java 源码编译）不受影响所以容易被忽略，只有实际打包/装机才会暴露。已把三个多余 jar 移出 `app/libs/`（备份在仓库外的 `_removed_libs_backup/`，未删除），`assembleDebug` 现在能正常出包。

---

## 4. 技术风险与规避 (Technical Risks)

*   **Z-Index 竞争**: PAX POSLink 启动交易时可能会弹出自带的 PIN Pad 或签名对话框。需确保这不会与我们现有的"数字座舱"全屏动效产生层级冲突（导致黑屏或闪烁）。
*   **生命周期冲突**: 如果 ID TECH 的 USB 读卡器和 PAX 内部 AIDL 服务同时初始化，需验证 `HardwareFactory` 是否能正确隔离两者的监听器回调，避免状态混乱。
*   **DAL 签名不确定性**（v1.1 新增）：见 §2.1，本地 stub 未经厂商真实 jar 校验，真机行为未知。
*   **多路径并存**（v1.1 新增）：见 §1.3，四条独立的 PAX DAL/POSLink 访问路径可能相互干扰（重复初始化、状态不同步）。

---

## 5. 后续需确定的 API 细节

- [x] ~~确认 `REFUND` 的准确 `transType` 编码~~ **v1.2 已查明**：官方枚举无 REFUND，应为 `RETURN=3`，当前代码写的 `5` 是 `POSTAUTH`，是 bug，见 §2.2/§6。
- [x] ~~确认是否需要 Logon (ManageRequest) 才能激活 AIDL 连接~~ **v1.2 基本解决**：官方 AIDL 示例代码未要求签到步骤，见 §2.2。
- [x] ~~AndroidManifest.xml 增加 `<queries>` 声明~~ **v1.2 已修复**。
- [ ] **获取**: 缺失的 `NeptuneLiteApi.jar` 原厂包。**已确认不在 `paxstore-3rd-app-android-sdk`（GitHub）中**——该仓库只含 PAXSTORE 平台服务模块（参数下发/更新检测/CloudMessage/GoInsight），与 NeptuneLite/DAL 硬件层无关，是两条不同产品线，不要按它下载了事。**待定问题**：NeptuneLite/DAL 的正式获取渠道（PAX Developer Portal 是否有独立条目 / 终端固件自带 / 需联系经销商单独申请）尚未确认，本次先搁置，待后续跟进。
- [ ] **确认**: **批次结算 (Batch Close)**。确认 IM30 是否需要 App 定时下发 `BatchRequest` (TransType=6/7?) 还是 BroadPOS 侧自动结算。这直接影响商户资金到账。
- [ ] **确认**: **终端参数 (Tags) 配置**。对于选定的收单行，是否需要通过 `ManageRequest` 注入特定的终端国家代码 (9F1A) 或货币代码 (5F2A)。
- [x] ~~修复 §3 列出的代码走查问题（3.1-3.4、3.6、3.7）以及 `transType` bug~~ **v1.3 已修复**，见 §3。
- [x] ~~`assembleDebug` 因 AAR/JAR 重复打包而失败~~ **v1.3 已修复**，见 §3.9。
- [ ] **架构**: §1.3 的四路径并存问题，制定收敛/迁移计划。
- [ ] **接入配置**: §3.8 `hardwareVendor` 硬编码，未接入云配置——单独排期。

---

## 6. 上线前必须完成的任务清单 (按阻断程度排序)

**已完成（v1.3）**：
1. ✅ `startCardDetection` 空实现（§3.1）
2. ✅ `cancelCurrentTransaction` 空实现，改用真实 `CancelTrans()`（§3.2）
3. ✅ `AndroidManifest.xml` 补 `<queries>` 声明
4. ✅ `PaxPaymentProvider.kt` 把 REFUND 的 `transType` 从 `5`（POSTAUTH）改成 `3`（RETURN）
5. ✅ `HardwareFactory` 缓存与实例复用问题（§3.3/§3.4）
6. ✅ `PaxScannerProvider` mock-fallback、句柄泄漏、回调线程问题（§3.5/§3.6/§3.7）
7. ✅ `assembleDebug` 因 AAR/JAR 重复打包失败的问题（§3.9）——**这一条在这批修复之前是全项目级别的阻断，不分厂商**

**仍未做（不阻断日常开发，但要在正式切 PAX 前处理）**：
8. 收敛 §1.3 的四路径并存问题（新 HAL vs. 老 `PaxScannerManager`/`PaymentService`/直接 DAL 调用）
9. `hardwareVendor` 接入云配置（§3.8）
10. 修正 `docs/system_architecture.md` v2.14 中过度承诺的"100% 隔离"表述

**仍卡在外部依赖上（等待 §5 的 NeptuneLite/DAL 申请结果）**：
11. 拿到 `NeptuneLiteApi.jar` 或等效物，用真实签名重新校验本地 stub——`PaxHardwareProvider.getSerialNumber/getFirmwareVersion`、`PaxScannerProvider` 全部依赖它，真机行为在拿到之前无法确认
12. 批次结算 (Batch Close)、终端参数 (Tags) 配置两项待确认（不阻断日常开发，阻断正式收单上线）

---

## 7. 版本记录
*   **v1.0 (2026-08-06)**: 初始版本。基于 POSLink Android V1.17.00 与 HAL 架构建立。
*   **v1.1 (2026-08-06)**: 补充代码走查发现的问题（§3）、SDK 完整性核对结果（§2.1，含全盘搜索与逐字节校验）、新发现的 Semi-Integration V2.03.00 包、澄清 PAXSTORE SDK 与 NeptuneLite/DAL 无关、新增架构冲突说明（§1.3）与上线前任务清单（§6）。
*   **v1.2 (2026-08-06)**: 用 `pdftotext` 提取已下载的 `API_Guide.pdf`/`AIDL_Guide.pdf` 核实了 §5 三项"待确认"中的两项：REFUND transType 从"未知风险"确认为**真实 bug**（当前 `5` 是 POSTAUTH，应为 `RETURN=3`）；Logon 必要性依据官方 AIDL 示例代码基本排除。`<queries>` 声明已按官方原文加入 `AndroidManifest.xml`。§6 任务清单按"是否依赖外部 NeptuneLite/DAL 申请"重新分组。
*   **v1.3 (2026-08-06)**: 实施了 §6 里不依赖 NeptuneLite/DAL 的全部代码修复（3.1-3.4、3.6、3.7、transType bug），`./gradlew compileDebugKotlin`/`assembleDebug` 均已验证通过。修复过程中发现 `app/libs/` 混用 AAR+JAR 导致 `assembleDebug` 此前一直构建失败（§3.9），这是本轮发现的问题里唯一一个不分厂商、影响全项目的阻断项，已一并修复。§3.5 mock-fallback 判定为"部分解决"（PaxScannerProvider 已加，PaxPaymentProvider 判定不需要，因为 `isSimulationMode` 已在更上层拦截）。§3.8（`hardwareVendor` 接入云配置）与 §1.3（四路径收敛）判定为范围更大，本轮不动，留待专门排期。
