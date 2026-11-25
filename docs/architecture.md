# NeoTalkie 架构

交付两个 Android 应用：`com.neotalkie.launcher` 桌面和 `com.neotalkie.talk` 对讲。两 APK 使用相同开发签名，独立进程、独立私有文件与生命周期。共享 Java 命名空间 `com.neotalkie.device` 是代码组织方式，不代表共享 Android UID 或数据。禁止使用 sharedUserId。

桌面持有设备状态和 HOME 入口，通过 Android 系统设置首页访问 Wi-Fi 及其他系统配置，或使用手机配网。对讲持有语音配置、麦克风轮次和上下文。桌面不依赖语音密钥，不包含 OkHttp/Okio 或模型代码，也不申请麦克风权限。桌面固定入口为对讲、设备设置、系统设置和手机配网。手机配网提供前台五分钟 BLE 会话，承接物理授权、加密配置和 Wi-Fi 连接状态。对讲菜单中的系统状态通过明确的组件目标进入桌面；语音连通检查留在对讲 APK。公共 UI/电源/音量代码从 `android/common/` 编译进两个 APK，不作为独立安装包。

## 工程与依赖边界

根目录是多工程集合，使用 Makefile 调度构建，不是 npm workspace。完整目录树见 [README](../README.md)。两个 APK 分别位于 `android/launcher` 和 `android/talk`。

- `android/` 内两个应用各有 Manifest、资源和 Java 源码；`common/` 由构建脚本分别编译进两个 APK。`scripts/` 直接调用 Android SDK、JDK 和 Python，不要求 Node 或 Gradle。
- `tools/` 独立声明 Playwright、PNG 和图片比较依赖，包含离线模拟器与共享 UI 校验工具。它读取 `config/radio-ui/` 并生成 Android/模拟器适配代码，不打入设备运行包。

桌面「系统设置」发送 `Settings.ACTION_SETTINGS`，打开系统设置首页；「手机配网」仍进入 NeoTalkie 的 BLE 页面。系统设置由设备 ROM 提供，本仓库不实现或修改它。

CI 从工具锁文件安装工具，运行各工程离线测试和 UI 图册检查。私有配置、签名、依赖缓存与构建输出均留在忽略目录，不作为工程源码。

## 跨应用 PTT

设备 ROM 的 F1 和 `com.zello.ptt.down/up` 是平台输入协议，名称保持原样，不代表依赖 Zello。前台页面与息屏前页面接收输入，同进程用 PttHandoff 去重。桌面按下 PTT 后创建随机短时令牌，并显式启动对讲 MainActivity。对讲查询桌面的 `content://com.neotalkie.launcher.ptt`，确认同一令牌仍按住且未超过 30 秒才开始采音。快速松手、过期令牌、桌面进程重启或查询失败均不得启动录音。

PTT provider 和桌面内部页面由 `com.neotalkie.launcher.permission.CONTROL` 签名权限保护；`held` 查询与 `release` 操作仅接受令牌，不读写语音配置。松手由 ROM 广播、按键和受保护的释放接口完成。两个 APK 同签名，签名密钥只保存在忽略目录。物理广播时序、冷启动和拔线息屏仍必须用真机验收。

## 手机与设备

设备助手提供 BLE 扫描、连接、固定 UTF-8 样本分包和真实回执校验，不连接云平台、不要求登录。Wi-Fi 密码仅为用户主动输入并加密下发，不保存到小程序存储，不收集语音密钥。桌面持有仅前台开放的 BLE GATT 服务，退出时清理连接并恢复蓝牙状态。名称匹配不证明身份。协议和现场验收见 [BLE 配网通路验证](ble-provisioning.md)。

0.2.1 由桌面的 WifiProvisionSession 处理会话加密与授权、WifiConnector 操作系统 Wi-Fi；两者不依赖对讲 APK。密码仅交给系统配置，状态通过带 MAC 的 BLE 回执返回。对讲设置由桌面通过版本化本机接口转交给对讲，配置由对讲校验与保存；当前未实现该接口。设备尚未联网时必须仍可完成首次配网。

## 配置和构建

`config/device.example.json` 仅为空字段示例。真实配置通过显式 ADB 命令写入对讲应用私有 files/config.json，权限 0600；不在命令参数或日志中打印值。prompt 从 `config/prompts/default.txt` 编译进对讲 APK。用户必须配置自己的模型或 endpoint ID。

当前输出是可用 ADB run-as 配置的开发 APK，debuggable=true。正式发行前需定义生产签名与非调试的配置通路。模型服务是用户自行配置的外部供应商 API，不是本仓库的服务端。
