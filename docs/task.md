# 任务列表 (PAX IM30 自助洗车系统)

- `[x]` 步骤 1: 初始化 Gradle 项目结构与规则文件
  - `[x]` 创建根目录 `build.gradle`, `settings.gradle`, `gradle.properties`
  - `[x]` 创建 App 模块 `app/build.gradle`, `AndroidManifest.xml`
  - `[x]` 创建 `.cursorrules` AI 规则定义文件
- `[x]` 步骤 2: 创建 UI 自适应布局与资源文件
  - `[x]` 编写 `colors.xml` 与 `styles.xml`（高端暗黑系 Kiosk 风格）
  - `[x]` 编写 `activity_main.xml`（主套餐选择界面，自适应 640x480）
  - `[x]` 编写 `activity_ad.xml`（全屏广告播放器布局）
  - `[x]` 编写 `dialog_payment.xml`（全屏支付引导与二维码展示弹窗）
- `[x]` 步骤 3: 编写核心 Kotlin 业务代码
  - `[x]` 编写 `BaseAdActivity.kt`（全局触摸监听，60s 倒计时）
  - `[x]` 编写 `AdActivity.kt`（广告循环播放，点击一触即退）
  - `[x]` 编写 `SerialPortManager.kt`（`/dev/ttyS1` 串口 Hex 指令封装）
  - `[x]` 编写 `PaymentService.kt`（Stripe/ZXing 扫码及卡支付接口 Stub）
  - `[x]` 编写 `PaxScannerManager.kt`（NeptuneLite 扫码头控制 Stub）
  - `[x]` 编写 `MainActivity.kt`（主流程控制：Kiosk 锁屏、消费选择、洗车工作计时）
- `[x]` 步骤 4: 项目编译与验证
  - `[x]` 校验整体逻辑是否正确，提供使用说明与模拟调试方法

---
## 品牌升级任务 (GoldSky 定制)
- `[x]` 步骤 5: 集成 GoldSky 品牌资源
    - `[x]` 创建 `ic_goldsky_logo.xml` 矢量图形
    - `[x]` 更新 `colors.xml` 定义品牌色彩体系
- `[x]` 步骤 6: 升级 UI 布局
    - `[x]` 重构 `activity_main.xml` (加入 Logo 和 Footer)
    - `[x]` 重构 `activity_ad.xml` (加入品牌 Slogan)
    - `[x]` 重构 `dialog_payment.xml` (配色升级)
- `[x]` 步骤 7: 代码逻辑调整
    - `[x]` 更新 `MainActivity.kt` 中的品牌文本
