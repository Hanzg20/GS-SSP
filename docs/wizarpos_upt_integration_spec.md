# WizarPOS Q3mini UPT 无人值守集成规格书 (GS-SSP Integration)

本文档定义了 GS-SSP 平台在 **WizarPOS Q3mini UPT** 硬件上的生产级集成标准。

## 1. 硬件接口定义 (Physical Mapping)

Q3mini UPT 背部接口从左至右定义如下：

| 物理端口 | 推荐驱动 | 逻辑 ID | 用途说明 |
| :--- | :--- | :--- | :--- |
| **Digit IO (左)** | `ExtBoardDevice` | N/A | **继电器直接控制**。通过 `triggerRelayOn(0/1)` 实现物理开关（如洗车水泵、充电桩接触器）。 |
| **MDB Slave (中)** | `ExtBoardDevice` | ID_SERIAL_EXT (2) | **售货机协议**。通过 `pollEvent` 处理 VMC 状态机交互。 |
| **Console (右)** | `SerialPortDevice` | ID_SERIAL_EXT2 (6) | **RS232 通讯**。用于外接 DEX 控制器或第三方 HEX 指令板卡。 |

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
