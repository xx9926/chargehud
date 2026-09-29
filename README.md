# 充电悬浮窗 ChargeHud

一个只关心充电的 Android 小工具：把**实时充电功率（W）**、**电池温度（℃）**（也可以再加上电压和电流）
钉在屏幕最上层，方便插着充电器时直接看到当前充得多快、机身有多热。

> 下载：**[Releases](https://github.com/xx9926/chargehud/releases/latest)** 里有可直接安装的 apk。

- 悬浮窗默认两行（功率 / 温度），在「外观」里可以勾选要显示哪几项：功率 / 温度 / 电压 / 电流，
  四行都显示时窗口自动变高，一行都不勾则整个窗口收起
- 颜色 / 字号 / 底色 / 位置都可调，设置页分「外观」「位置」「更多」三段，点标题展开收起
- 拖动随意摆放，单击回到设置页；「位置」里有锁定开关，锁上后拖不动
- 下拉状态栏磁贴「充电悬浮」，点一下开 / 关悬浮窗，磁贴颜色当场跟着变（Quick Settings Tile）
- 常驻通知显示当前功率与温度，带「关闭悬浮窗」和「锁定 / 解锁」两个按钮；
  不想让通知占位可以在「更多」里关掉常驻，代价是后台更容易被系统回收
- 开机自动恢复上次的开关状态与位置
- 无任何联网、无广告、无第三方 SDK，依赖只有 androidx core / appcompat / material

## 悬浮窗手势

| 操作 | 效果 |
| --- | --- |
| 拖动面板 | 移动悬浮窗（贴到状态栏时从面板下方的透明抓手区起手） |
| 单击面板 | 打开设置页（锁定状态同样有效） |

位置也可以用设置页里的 ▲▼◀▶ 按钮逐 5 px 微调（支持长按连发），比手拖更准。

## 读数从哪来

第三方应用读不到真实的充电电流，本应用按可用性依次降级：

1. **FRAMEWORK** — `BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)`，取最近几秒的**电流幅值均值**。
   正负号不信 HAL（本机这颗天玑在 PC USB 口上是脉冲充电，瞬时电流本身就在 +0.47 A ↔ −0.47 A 之间交替），
   而是按框架报的充电状态统一盖：**插电 / 充电中显示正数，拔掉充电器后显示负数（电池在放电）**；
   插拔瞬间会清空采样窗，不会把上一个方向的读数拖过来。
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
- 当前最新 v1.4 直链：<https://github.com/xx9926/chargehud/releases/download/v1.4/chargehud-v1.4.apk>
  （2,028,064 字节，MD5 `d633d31a6d12a5b5999498c8fef22221`）

安装包用 **debug keystore** 签名（个人调试签名），把 apk 传到手机上点开安装即可；
国内网络直连 GitHub 常被重置，下载时需要走代理。

用 adb 安装：

```sh
adb install -r -t chargehud-v1.4.apk
```

装好后打开「显示悬浮窗」开关即可。MIUI / HyperOS 上还需要注意三件事：

- **通知默认是关的**：应用信息 → 通知管理 → 打开「允许通知」，否则常驻通知不显示，
  通知上的「关闭悬浮窗 / 锁定」按钮也就看不到。
- **磁贴要手动添加**：下拉控制中心 → 编辑 → 在「未添加开关」里点一下「充电悬浮」即可加入。
- 放行「显示悬浮窗」和「自启动」，并把电池优化设为不限制，否则后台服务会被回收。

## 更新记录

| 版本 | 日期 | 说明 |
| --- | --- | --- |
| v1.4 | 2026-09-30 | 修复功率正负号随插拔颠倒：显示方向改由充电状态判定（插电 / 充电中为正，拔掉为负），采样窗在插拔瞬间清空，不再把上一个方向的读数拖过来 |
| v1.3 | 2026-09-30 | 悬浮窗显示内容可选（功率 / 温度 / 电压 / 电流）；常驻通知可关；设置页三段可折叠；「位置」新增锁定开关并去掉长按锁定手势；修复磁贴要点开一次、再下拉才变蓝的问题；开关配色与取色弹窗圆角化 |
| v1.2 | 2026-09-29 | 修复控制中心不列第三方磁贴：`TileService` 权限名应为 `BIND_QUICK_SETTINGS_TILE` |
| v1.1 | 2026-09-29 | release 构建启用 R8 混淆，包体从 6.4 MB 降到 1.9 MB |
| v1.0 | 2026-09-29 | 首个可用版本：悬浮窗、磁贴、常驻通知、开机恢复 |

完整说明见 [Releases](https://github.com/xx9926/chargehud/releases) 页面。

## 目录结构

```
app/src/main/java/com/chargehud/app/
├── MainActivity.kt        设置页（外观 / 位置 / 更多三段，可折叠）
├── HudService.kt          悬浮窗前台服务：窗口、手势、拖动与锁定
├── BatteryReader.kt        取数与降级逻辑
├── Prefs.kt               SharedPreferences 封装
├── ColorPickerDialog.kt   SV 方块 + 色相条取色弹窗
├── HudTileService.kt      下拉控制中心磁贴（Quick Settings Tile）
└── BootReceiver.kt        开机恢复
```

## 说明

本工程为独立实现，所有代码均基于 Android 公开 API 编写。
