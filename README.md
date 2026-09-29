# 充电悬浮窗 ChargeHud

一个只有两个功能的 Android 小工具：把**实时充电功率（W）**和**电池温度（℃）**钉在屏幕最上层，
方便插着充电器时直接看到当前充得多快、机身有多热。

- 两行悬浮窗，颜色 / 字号 / 底色 / 位置都可调
- 拖动随意摆放，长按上锁防止误碰，单击回到设置页
- 通知栏快捷开关 + 锁屏磁贴（Quick Settings Tile）
- 开机自动恢复上次的开关状态与位置
- 无任何联网、无广告、无第三方 SDK，依赖只有 androidx core / appcompat / material

## 悬浮窗手势

| 操作 | 效果 |
| --- | --- |
| 拖动面板 | 移动悬浮窗（贴到状态栏时从面板下方的透明抓手区起手） |
| 单击面板 | 打开设置页 |
| 长按面板 | 锁定 / 解锁位置 |

位置也可以用设置页里的 ▲▼◀▶ 按钮逐 5 px 微调，比手拖更准。

## 读数从哪来

第三方应用读不到真实的充电电流，本应用按可用性依次降级：

1. **FRAMEWORK** — `BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)`，取最近几秒的**带符号均值**。
   部分机型（如 Redmi 12C 的 MTK 平台）在 PC USB 口上是脉冲充电，电流会正负交替，所以这里保留符号：
   **功率显示为负数是正确行为**，代表这一段时间净能量是流出的。
2. **SYSTEM_FILE** — `/sys/class/power_supply/**/current_now`。绝大多数 ROM 已对普通应用关闭读取，
   需要在设置里手动开启，失败会自动降级。
3. **CHARGE_COUNTER** — 用库仑计 `BATTERY_PROPERTY_CHARGE_COUNTER` 差分算电流。
4. **ESTIMATED** — 用电池容量 × 电量百分比变化速率估算，只能看个趋势。

电压来自 `EXTRA_VOLTAGE`（mV），温度来自 `EXTRA_TEMPERATURE`（0.1 ℃），
标称容量通过反射 `PowerProfile.getAveragePower("battery.capacity")` 获取，失败时可手填。

已验证机型：Redmi 12C（M2104K10AC，Android 14 / HyperOS V14.0.8.0），1080×2400，density 2.75。

## 构建

环境：JDK 17+（实测 JDK 21）、Android SDK platform 34、随仓库的 Gradle Wrapper。

```sh
sh ./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

离线环境下加 `--offline`（依赖需已在本机 Gradle 缓存中）。

## 安装与首次使用

```sh
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

打开「显示悬浮窗」开关即可。MIUI / HyperOS 需要在应用信息里放行
「显示悬浮窗」和「自启动」，并把本应用设为电池优化不限制，否则后台服务会被回收。

## 目录结构

```
app/src/main/java/com/chargehud/app/
├── MainActivity.kt        设置页（外观、位置、数据源、开关）
├── HudService.kt          悬浮窗前台服务：窗口、手势、拖动与锁定
├── BatteryReader.kt        取数与降级逻辑
├── Prefs.kt               SharedPreferences 封装
├── ColorPickerDialog.kt   SV 方块 + 色相条取色弹窗
├── HudTileService.kt      通知栏磁贴
└── BootReceiver.kt        开机恢复
```

## 说明

本工程为独立实现，所有代码均基于 Android 公开 API 编写。
