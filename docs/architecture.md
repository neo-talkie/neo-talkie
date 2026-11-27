# NeoTalkie 架构

核心功能由两个 Android 应用提供：`com.neotalkie.launcher` 桌面和 `com.neotalkie.talk` 对讲。两 APK 使用相同开发签名，独立进程、独立私有文件与生命周期。共享 Java 命名空间 `com.neotalkie.device` 是代码组织方式，不代表共享 Android UID 或数据。禁止使用 sharedUserId。

桌面持有设备状态和 HOME 入口，通过 `Android Settings`（原“系统设置”）访问 Wi-Fi 及其他系统配置，或使用「手机配置」。对讲持有语音配置、麦克风轮次和上下文。桌面不依赖语音密钥，不包含 OkHttp/Okio 或模型代码，也不申请麦克风权限。桌面固定入口为 `NeoTalkie对讲`、`设备设置`、`Android Settings` 和 `手机配置`，每个入口带有图标与功能解释字段。设备设置提供网络状态、系统状态以及「软件更新」全量 OTA（从 GitHub Releases 读取 `ota-manifest.json`，内置 `ISRG Root X1` 根证书并校验 SHA-256，在具备静默安装权限的设备上经 `PackageInstaller` 无弹窗升级全部应用——Android 5.1 需预置到 `/system/priv-app/` 获取 `INSTALL_PACKAGES`，Android 6.0+ 可通过 `dpm set-device-owner` 激活——且始终最后安装桌面自身；见[OTA 静默更新与发布流水线](ota.md)）。手机配置提供前台五分钟 BLE 会话，承接物理授权、加密配置和 Wi-Fi 连接状态。对讲菜单中的系统状态通过明确的组件目标进入桌面；语音连通检查留在对讲 APK。公共 UI/电源/音量代码从 `android/common/` 编译进两个 APK，不作为独立安装包。

## 工程与依赖边界

根目录是多工程集合，使用 Makefile 调度构建，不是 npm workspace。完整目录树见 [README](../README.md)。两个核心 APK 分别位于 `android/launcher` 和 `android/talk`。

- `android/` 内两个应用各有 Manifest、资源和 Java 源码；`common/` 由构建脚本分别编译进两个 APK。`scripts/` 直接调用 Android SDK、JDK 和 Python，不要求 Node 或 Gradle。
- `android/audio-link/` 交付 NeoTalkie Audio Link（音频互联）。桌面通过系统应用列表发现它。在 mx_b5 Android 5.1 上，打开应用时先保存蓝牙开关及两个角色的原始属性值，再写入并读回 `persist.service.bt.a2dp.sink` 与 `persist.service.bt.hfp.client`，按需统一重启蓝牙。系统蓝牙负责配对、A2DP 媒体解码播放与 HFP 通话。主机根据音频用途选择协议，PTT 只控制 HFP 语音通路。音频互联 APK 的前台服务用厂商音频参数控制 HFP 麦克风通路，并通过厂商 HID Device 接口发送 Globe。应用在前台处理 PTT，提供普通 PTT 和 Fn 首尾点按两种模式。两种模式均使用 Mac 默认按键配置。首页和模式页复用公共音量条，在前台接管旋钮并显示当前档位，SCO 接通时调节 HFP 扬声器音量，其余时间调节媒体音量。首页显示手台蓝牙名，配对入口和模式选择纵向排列。选择配对时先读取当前可发现状态。已获系统授予 WRITE_SECURE_SETTINGS 的 mx_b5 安装可直接开启 120 秒可发现窗口，其余安装由 Android 请求用户确认。首次配对码由系统处理。界面可见期间绑定音频服务，失去焦点即释放 PTT；最后一个界面停止并解绑时，服务撤销音频和 HID、恢复进入时的两个角色属性与蓝牙开关状态，再结束自身。快速重开时以最近一次请求为准。见[真机验证](verification.md)。
- `tools/` 独立声明 Playwright、PNG 和图片比较依赖，包含离线模拟器与共享 UI 校验工具。它读取 `config/radio-ui/` 并生成 Android/模拟器适配代码，不打入设备运行包。

桌面 `Android Settings` 发送 `Settings.ACTION_SETTINGS`，打开系统设置首页；「手机配置」仍进入 NeoTalkie 的 BLE 页面。系统设置由设备 ROM 提供，本仓库不实现或修改它。

## 手台顶栏双模式与桌面规范

160×128 手台屏幕的共享顶栏（`StatusStrip`）采用双模式设计规范，统一由 `config/radio-ui/contract.json` 定义尺寸与色彩常量：

| 模式 | 高度与分割线 | 时间点阵（3×5） | 电池与网络 | 适用页面 |
| --- | --- | --- | --- | --- |
| **大顶栏（`STANDARD`）** | `27 px`，白色分割线 `y = 26` | `scale = 4`（20 px 高） | 竖向电池 + 充电闪电 + 19×17 Wi-Fi（或 14 px 文字） | 对讲主屏（强待机时钟可读性）、菜单与状态信息页 |
| **小顶栏（`COMPACT`）** | `16 px`，低干扰分割线 `#3b5952`（`y = 15`） | `scale = 2`（10 px 高） | 右侧横向 14×8 电池 + 迷你闪电 + 13×10 紧凑 Wi-Fi（或 10 px 文字） | Launcher 桌面及后续需要更大主区的内容密集页面 |

Launcher 桌面使用小顶栏模式，主区（`y = 16..103`）采用单屏聚焦应用卡片：居中 `44×44` 切角图标底托（`#183c35` 底 + `#f04e1a` 焦点描边，内置应用绘制 `28×28` 像素图标，第三方应用优先显示其 APK 图标并回退到 2×2 像素网格）、`17 px` 居中应用名、`13 px` 居中功能解释字段（`#b2c4be`，超宽自动缩至最低 11 px），右上角点阵页码与右侧纵向定位点条配合实体上下键切换。

CI 从工具锁文件安装工具，运行各工程离线测试和 UI 图册检查。私有配置、签名、依赖缓存与构建输出均留在忽略目录，不作为工程源码。

## 手台设计工作台

独立包 `@neotalkie/design-studio` 维护渲染源码、场景与 Android 页面的映射及冻结设计稿。固件拥有 `config/radio-ui/` 的正式场景输入和生成器。固件内的 `tools/simulator/` 固定工作台发行文件，`studio-release.json` 记录内容摘要。工作台由独立包的 `npm start` 启动。固件独立检出可出图并执行一致性校验。每个场景使用稳定 ID，对应原生应用包名、Activity 和输入参数。工作台按同一 ID 展示有来源及版本的 Android 截图。真机采集由操作者明确触发，实际图片保存在本机。见[UI 一致性](radio-ui-contract.md)。

## 手台音量键与音量条

Android 将音量键事件分发给当前持有焦点的窗口。每个自研 APK 都从 `android/common/` 复用音量条，在自己的前台窗口接入。同一 Activity 窗口内切换首页、菜单或模式页，只需接入一次。新 Activity 或拥有独立焦点窗口的应用内对话框，需要在该窗口分发按键并显示音量条。打开系统设置或系统配对弹窗后，音量反馈由 ROM 负责。

- **按键**　在窗口的 `dispatchKeyEvent` 或等效入口把音量键的按下、重复和松开交给共享组件。`handleKey` 返回 `true` 时立即消费事件，避免同一格旋钮同时触发系统面板或重复调档。方向键继续用于页面导航。
- **显示**　自绘页面在 160×128 逻辑画布上最后绘制 `VolumeStrip`。标准 Android 控件页面把 `VolumeStripOverlay` 放在内容上方。旋钮方向适配、实际档位读数和自动收起时间都由共享组件处理。Activity 暂停或对话框关闭时调用 `hide()`。
- **音频通路**　页面按当前播放通路选择音量流。桌面、对讲和 A2DP 媒体播放调节媒体音量，音频互联在 HFP 的 SCO 通路接通时调节通话扬声器音量。音量条读取被调节音量流的当前档位和最大档位。

自绘页面可参考[桌面首页](../android/launcher/src/com/neotalkie/device/HomeActivity.java)，标准控件页面可参考[音频互联](../android/audio-link/src/com/neotalkie/audiolink/AudioLinkActivity.java)。

## 跨应用 PTT

设备 ROM 的 F1 和 `com.zello.ptt.down/up` 是平台输入协议，名称保持原样，不代表依赖 Zello。前台页面与息屏前页面接收输入，同进程用 PttHandoff 去重。桌面按下 PTT 后创建随机短时令牌，并显式启动对讲 MainActivity。对讲查询桌面的 `content://com.neotalkie.launcher.ptt`，确认同一令牌仍按住且未超过 30 秒才开始采音。快速松手、过期令牌、桌面进程重启或查询失败均不得启动录音。

PTT provider 和桌面内部页面由 `com.neotalkie.launcher.permission.CONTROL` 签名权限保护；`held` 查询与 `release` 操作仅接受令牌，不读写语音配置。松手由 ROM 广播、按键和受保护的释放接口完成。两个 APK 同签名，签名密钥只保存在忽略目录。物理广播时序、冷启动和拔线息屏仍必须用真机验收。

## 手机与设备

设备助手提供 BLE 扫描、连接、固定 UTF-8 样本分包和真实回执校验，不连接云平台、不要求登录。Wi-Fi 密码仅为用户主动输入并加密下发，不保存到小程序存储，不收集语音密钥。桌面持有仅前台开放的 BLE GATT 服务，退出时清理连接并恢复蓝牙状态。名称匹配不证明身份。协议和现场验收见 [BLE 配网通路验证](ble-provisioning.md)。

0.2.1 由桌面的 WifiProvisionSession 处理会话加密与授权、WifiConnector 操作系统 Wi-Fi；两者不依赖对讲 APK。密码仅交给系统配置，状态通过带 MAC 的 BLE 回执返回。对讲设置由桌面通过版本化本机接口转交给对讲，配置由对讲校验与保存；接口由只接受同签名调用的 CompanionProvider 实现。设备尚未联网时必须仍可完成首次配网。

## 配置和构建

`config/device.example.json` 仅为空字段示例。用户在手机上填写后，通过 BLE 授权的局域网会话写入对讲应用私有 files/config.json，权限 0600；不在命令参数或日志中打印值。prompt 从 `config/prompts/default.txt` 编译进对讲 APK。用户必须配置自己的模型或 endpoint ID。

当前输出是可用 ADB run-as 配置的开发 APK，debuggable=true。使用者可通过「手机配置」完成设置。模型服务是用户自行配置的外部供应商 API，不是本仓库的服务端。

## 手机管理与本地记录

桌面 APK 统一负责系统配网、管理鉴权和请求转发。各子应用定义自己的配置接口、校验并保存数据。管理界面按应用定制页面、字段样式和交互，一个应用可以使用多张表单。已授权的手机可从同一 Wi-Fi 在对讲期间建立管理会话。见[手台配置服务与子应用的架构约定](device-configuration.md)。

### 当前固件行为

0.2.3 将 BLE 配网页的临时连接和 ManagementService 的后台连接分开。在手台确认绑定后，已绑定手机通过 Wi-Fi 双向认证，在配置页关闭后仍可按需访问。两类端口均只绑定 Wi-Fi 地址。管理接口使用每次连接独立的 AES-GCM 密钥，配置版本防止覆盖其他更新。对讲 APK 的 CompanionConfig 保存模型、密钥、频道和记忆期限，对讲首页下侧键显示活动频道名称，并按配置顺序循环选择。mx_b5 的实体下侧键按下时报告 F8，松开后 ROM 还会补发扫描码为 0 的向右键。对讲首页只在 F8 首次按下时切换频道。切换活动频道后生成新上下文。对讲首页的 EXIT 先收起本轮内容并返回讲话提示，保留已有上下文。只有已显示待机提示（含未配置提示）时才退出应用。每个频道使用 replySeconds 设置 30、60 或 120 秒的播报上限，流式合成按 24 kHz 单声道 16 位 PCM 样本数封顶。每轮回答末尾的 `<topic>` 概括用户当轮发言，最多六个汉字。手台在送入 TTS 前拆出主题，将其与本轮问答一同保存，并在上下文有效时用最近一轮主题替换“继续追问”。RecentTurns 在应用私有 SQLite 中保留最近 100 条文字问答、已返回的 token 用量与耗时，通过分页按需读取。试听由对讲 APK 的 TTS 播放。
