# 充电悬浮窗 ChargeHud

一个只有两个功能的 Android 小工具：把**实时充电功率（W）**和**电池温度（℃）**钉在屏幕最上层，
方便插着充电器时直接看到当前充得多快、机身有多热。

> 下载：**[Releases](https://github.com/xx9926/chargehud/releases/latest)** 里有可直接安装的 apk。

- 两行悬浮窗，颜色 / 字号 / 底色 / 位置都可调
- 拖动随意摆放，长按上锁防止误碰，单击回到设置页
- 下拉状态栏磁贴「充电悬浮」，点一下开 / 关悬浮窗（Quick Settings Tile）
- 常驻通知显示当前功率与温度，带「关闭悬浮窗」和「锁定 / 解锁」两个按钮
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
   部分机型（如本机这颗天玑平台）在 PC USB 口上是脉冲充电，电流会正负交替，所以这里保留符号：
   **功率显示为负数是正确行为**，代表这一段时间净能量是流出的。
2. **SYSTEM_FILE** — `/sys/class/power_supply/**/current_now`。绝大多数 ROM 已对普通应用关闭读取，
   需要在设置里手动开启，失败会自动降级。
3. **CHARGE_COUNTER** — 用库仑计 `BATTERY_PROPERTY_CHARGE_COUNTER` 差分算电流。
4. **ESTIMATED** — 用电池容量 × 电量百分比变化速率估算，只能看个趋势。

电压来自 `EXTRA_VOLTAGE`（mV），温度来自 `EXTRA_TEMPERATURE`（0.1 ℃），
标称容量通过反射 `PowerProfile.getAveragePower("battery.capacity")` 获取，失败时可手填。

已验证机型：Redmi Note 10 Pro（M2104K10AC / chopin，Android 13，HyperOS V14.0.8.0.TKPCNXM），1080×2400，440 dpi。

## 构建

环境：JDK 17+（实测 JDK 21）、Android SDK platform 34、随仓库的 Gradle Wrapper。

```sh
sh ./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk

sh ./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk —— R8 混淆 + 优化，约 1.9 MB
```

release 构建启用了 R8（`proguard-android-optimize.txt` + `app/proguard-rules.pro`）。
四个 manifest 组件由 AGP 自动 keep，反射只指向框架类 `PowerProfile`，因此不需要额外 keep 规则。
release 目前借用 debug 密钥签名，否则产物未签名、无法装机；换正式密钥请先建 keystore 并配 `signingConfig`。

离线环境下加 `--offline`（依赖需已在本机 Gradle 缓存中）。

## 安装与首次使用

不想自己编译的话，直接下载 Release 里的安装包（约 1.9 MB）：

- 最新版本列表：<https://github.com/xx9926/chargehud/releases/latest>
- 直链：<https://github.com/xx9926/chargehud/releases/download/v1.2/chargehud-v1.2.apk>

安装包用 **debug keystore** 签名（个人调试签名），把 apk 传到手机上点开安装即可；
国内网络直连 GitHub 常被重置，下载时需要走代理。

用 adb 安装：

```sh
adb install -r -t chargehud-v1.2.apk
```

装好后打开「显示悬浮窗」开关即可。MIUI / HyperOS 上还需要注意三件事：

- **通知默认是关的**：应用信息 → 通知管理 → 打开「允许通知」，否则常驻通知不显示，
  通知上的「关闭悬浮窗 / 锁定」按钮也就看不到。
- **磁贴要手动添加**：下拉控制中心 → 编辑 → 在「未添加开关」里点一下「充电悬浮」即可加入。
- 放行「显示悬浮窗」和「自启动」，并把电池优化设为不限制，否则后台服务会被回收。

## 目录结构

```
app/src/main/java/com/chargehud/app/
├── MainActivity.kt        设置页（外观、位置、数据源、开关）
├── HudService.kt          悬浮窗前台服务：窗口、手势、拖动与锁定
├── BatteryReader.kt        取数与降级逻辑
├── Prefs.kt               SharedPreferences 封装
├── ColorPickerDialog.kt   SV 方块 + 色相条取色弹窗
├── HudTileService.kt      下拉控制中心磁贴（Quick Settings Tile）
└── BootReceiver.kt        开机恢复
```

## 说明

本工程为独立实现，所有代码均基于 Android 公开 API 编写。
