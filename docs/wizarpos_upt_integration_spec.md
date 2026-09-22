# WizarPOS Q3mini UPT 无人值守集成规格书 (GS-SSP Integration)

本文档定义了 GS-SSP 平台在 **WizarPOS Q3mini UPT** 硬件上的生产级集成标准。

## 1. 硬件接口定义 (Physical Mapping)

Q3mini UPT 背部接口从左至右定义如下：

| 物理端口 | 推荐驱动 | 逻辑 ID | 用途说明 |
| :--- | :--- | :--- | :--- |
| **Digit IO (左)** | `ExtBoardDevice` | N/A | 10-Pin 端子，内含两组电气上独立的电路——**Pulse** (PIN1/2/8) 与 **Relay** (PIN6/7)，见 §1.1。当前 `DigitIoAdapter`（`core/data/.../dispense/adapter/DigitIoAdapter.kt`，是 `wash` 在 Q3mini/IM30 UPT 上出货的实际生产路径，见 `DispenseEngine.dispense()` 的选路逻辑）经 `triggerRelayOn(0)`/`triggerRelayOff(0)` 驱动 **Relay** 电路。 |
| **MDB Slave (中)** | `ExtBoardDevice` | ID_SERIAL_EXT (2) | **售货机协议**。通过 `pollEvent` 处理 VMC 状态机交互。 |
| **Console (右)** | `SerialPortDevice` | ID_SERIAL_EXT2 (6) | **RS232 通讯**。用于外接 DEX 控制器或第三方 HEX 指令板卡。 |

### 1.1 Digit IO 物理引脚、线色与信号功能对照表

实测于真机的 10-Pin 端子定义（2026-09-22，用户现场实测提供，未来任何 Digit IO 接线/排障都以此表为准，而非 SDK 文档里笼统的 "Digit IO" 描述）：

| PIN 编号 | 实测导线颜色 | 信号定义 (Signal Name) | 电压与硬件规格说明 (Electric Specs) |
| :--- | :--- | :--- | :--- |
| PIN 1 | 白色 | Pulse 1 (脉冲通道 1) | 12V 脉冲输出 (SDK 对应 `portNum = 0`) |
| PIN 2 | 绿色 | Pulse 2 (脉冲通道 2) | 12V 脉冲输出 (SDK 对应 `portNum = 1`) |
| PIN 3 | 蓝色 | DIN (数字输入检测) | 逻辑电平输入，支持 3.3V / 5V / 12V 状态检测 |
| PIN 4 | 橙色 | NC / 扩展控制端 | 保留 / 控制低边回路 |
| PIN 5 | 紫色 | GND (地线) | 系统公共地（外部电源负极须与此共地） |
| PIN 6 | 黄色 | RELAY_DC- (继电器驱动负极) | 12V 低边开关控制端（吸合时内部对地导通） |
| PIN 7 | 棕色 | RELAY_DC+ (继电器驱动正极) | 12V 电源输出端，用于驱动 12V 继电器线圈 |
| PIN 8 | 灰色 | Pulse / 辅助信号 | 扩展控制信号输出 |
| PIN 9 | 黑色 | GND (地线) | 系统公共地（与 PIN 5 内部互通） |
| PIN 10 | 红色 | +12V / +24V (VCC 直流输出) | 直流供电输出（受主板供电或 MDB 输入电压影响） |

**关键点：Pulse (PIN1/2/8) 和 Relay (PIN6/7) 是两组电气上独立的电路**，分别对应 `ExtBoardDevice` 两组不同的 API：

| 电路 | 引脚 | API | 说明 |
| :--- | :--- | :--- | :--- |
| Pulse | PIN1/2 (`portNum=0/1`)、PIN8 | `setPulseVoltage(int voltage)`、`triggerPulse(portNum, voltage, duration, interval, num)`、`triggerPulseUs(...)` | 12V 逻辑脉冲信号输出，硬件计时。`voltage`: 0=待机高电平/输出低电平，1=待机低电平/输出高电平；同一端口两个方法的 `voltage` 参数必须保持一致；`interval` 为**上一个脉冲结束到下一个脉冲开始**的间隔。 |
| Relay | PIN6 (DC-) / PIN7 (DC+) | `triggerRelayOn(port)` / `triggerRelayOff(port)`（当前 `DigitIoAdapter` 用法）、`triggerRelay(port, onMs, offMs, times)`（硬件计时版，官方 APIDemo 用法：`triggerRelay(0, 500, 500, 5)`） | 驱动外部 12V 继电器线圈的低边开关，用于直接切换较大功率负载（如水泵/电磁阀电源）。 |

**⚠️ 未确认，待与现场接线核实**：`DigitIoAdapter`（`core/data/.../dispense/adapter/DigitIoAdapter.kt`）目前用 `triggerRelayOn(0)` + `delay(500ms)` + `triggerRelayOff(0)` + `delay(500ms)` 的**软件计时**循环去驱动 **Relay** 电路（PIN6/7），模拟投币计数器式的多脉冲信用。这在电气上说得通（继电器触点闭合本身就是常见的投币脉冲模拟方式），但没有确认过洗车场站实际下游计时板/继电器板具体接在 PIN6/7 (Relay) 还是 PIN1/2/8 (Pulse)——如果下游板子期望的是逻辑电平脉冲而非继电器干接点，接线就接错了电路。另外，即使确认 Relay 电路是对的，SDK 也提供了**硬件计时**的 `triggerRelay(port, onMs, offMs, times)` 一次性下发整个脉冲序列，比 App 侧 `delay()` 循环（受 Android 协程调度/GC 抖动影响）更可靠，值得作为后续优化。

## 2. 支付集成：PAYWizard Socket 模式

GS-SSP 采用 **Local Semi-Integrated** 模式，通过内部 Socket 调用 WizarPOS 官方支付应用。

### 2.1 通讯规范
*   **地址**: `127.0.0.1:6666` (本地回环)
*   **帧封装**: `[4-byte Length (Big-Endian)] + [JSON Payload]`
*   **超时**: 60s (支付生命周期)

### 2.2 核心报文 (SALE)
**Request JSON**:
```json
{
  "transType": "SALE",
  "amount": "100",
  "orderNo": "GS-TXN-12345",
  "isPrint": "true"
}
```
**Success Response JSON**:
```json
{
  "resultCode": "0",
  "resultMsg": "SUCCESS",
  "transData": {
    "authNo": "123456",
    "refNo": "000000000001",
    "amount": "100"
  }
}
```

## 3. 软件工程要求 (Engineering Requirements)

### 3.1 权限清单 (Manifest)
必须包含以下权限以驱动 UPT 特有的硬件：
```xml
<uses-permission android:name="android.permission.CLOUDPOS_SERIAL" />
<uses-permission android:name="android.permission.CLOUDPOS_INNER_GPIO" />
<uses-permission android:name="android.permission.CLOUDPOS_MCUCTL" />
<uses-permission android:name="android.permission.INTERNET" /> <!-- Localhost Socket -->
```

### 3.2 混淆规则 (ProGuard)
针对 `ExtBoardDevice` 的 Parcelable 数据结构，必须禁止混淆：
```proguard
-keep class com.cloudpos.extboard.bean.** { *; }
```

## 4. 生产环境切换 (Production Deployment)

1.  **测试环境**: 使用 Maggie 提供的 `PaymentEmulator.apk`。
2.  **生产环境**:
    *   将模拟器替换为正式版 **Nuvei Payment App**。
    *   在 **WizarView (TMS)** 后台配置商户 MID/TID。
    *   GS-SSP 逻辑 100% 保持不变，实现零代码切换生产。

---

> [!CAUTION]
> **逻辑诚实性**: 
> 系统严禁伪造支付或结算成功。在未收到 Socket 返回的 `resultCode: 0` 前，HAL 层必须报告 `onFailure`，确保财务安全。

> [!TIP]
> **机型检测**: 
> 业务层代码应调用 `DeviceAdapter.getModel()` 以确保当前运行在 `WIZARPOS_Q3MINI` 模式。
