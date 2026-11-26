# NeoTalkie

An AI walkie-talkie, built around a small Android handset.

NeoTalkie 是独立的设备端项目，提供桌面、对讲和可选的蓝牙麦克风 APK。目标设备是 Android 5.1、160×128 屏幕的实体 PTT 手台；其他机型需要验证按键和权限。仓库不包含平台后台、云端部署、账号数据库、私有密钥或录音。

| 交付物 | 目录 | 功能 |
| --- | --- | --- |
| NeoTalkie 桌面 | `android/launcher/` | HOME、应用入口、网络/系统信息、打开 Android 系统设置首页 |
| NeoTalkie 对讲 | `android/talk/` | 实体 PTT、ASR → AI → TTS、短期上下文、语音服务诊断 |
| 蓝牙麦克风 | `android/hfp-mic/` | mx_b5 专用，打开后启用电脑蓝牙麦克风模式 |

**在手台打开「手机配置」即可用微信助手管理本机。** 首次连接核对两端确认码并按 MENU。启用后台管理后，在对讲期间打开助手即可经同一 Wi-Fi 重连。助手可以配置 Wi-Fi、模型、密钥及频道，按需拉取最近 100 条问答和用量。[操作说明](docs/ble-provisioning.md)

开始使用：[新设备启用](docs/getting-started.md)。结构与接口：[架构](docs/architecture.md)。验证边界：[验收记录](docs/verification.md)。

```sh
make setup
python3 android/scripts/setup.py
make android-build
make test
```

首次构建需按启用文档安装 JDK 17 和 Android SDK；`setup.py` 只下载并校验公开的 Java 依赖。测试只访问本机，不连接语音服务。

输出：`android/launcher/build/neotalkie-launcher.apk`、`android/talk/build/neotalkie-talk.apk`、`android/hfp-mic/build/neotalkie-hfp-mic.apk`。

目录按平台和用途组织：

```text
Neo-Talkie/
├── android/                Android 工程（Java + Android SDK）
│   ├── launcher/           桌面 APK
│   ├── hfp-mic/            可选的 mx_b5 蓝牙麦克风 APK
│   ├── talk/               对讲 APK
│   ├── common/             两个 APK 共用的 Java 源码
│   └── scripts/            构建、测试与 ADB 工具
├── tools/                  开发工具，独立 package.json 和锁文件
│   ├── simulator/          离线手台界面模拟器
│   ├── ui/                 共享 UI 生成、对照与截图检查
│   └── scripts/            开源内容检查
├── config/                 共享 UI 契约与公开配置示例
├── docs/                   架构、启用与验收文档
└── Makefile                跨工程命令入口
```

根目录不是 Node 项目，没有 `package.json` 或 `node_modules`。`make setup` 安装 `tools/node_modules`；Android 不依赖 Node 运行时。`common` 是编译时源码，模拟器是开发辅助，均不是额外的交付应用。

本机生成目录不进入 Git：`.tools/` 保存 SDK/JDK 和公开构建依赖，`data/` 保存私有配置、开发签名和验收产物，`android/*/build/` 保存 APK。覆盖安装需保留本机签名。

各工程说明：[Android](android/README.md) · [开发工具](tools/README.md)。

许可证：[Apache-2.0](LICENSE)。第三方组件与字体的声明分别保留在各应用的 `THIRD_PARTY.md` 和 `assets/NOTICE` 中。
