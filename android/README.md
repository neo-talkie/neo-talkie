# Android 工程

本目录交付桌面、对讲和可选的蓝牙麦克风 APK。`launcher/` 和 `talk/` 各自拥有 Manifest、资源、源码及测试；`common/` 是同时编译进两者的公共 Java 源码，不是第三个 APK。

当前使用 Python 脚本直接调用 JDK 17 与 Android SDK 构建，没有 Gradle 工程，也不使用 Node 运行时。电脑依赖与首次安装见 [新设备启用](../docs/getting-started.md)。

在仓库根目录执行：

```sh
make android-setup
make android-build
make android-test
make install SERIAL=YOUR_SERIAL
```

只构建一个应用可运行 `python3 android/scripts/build.py launcher`、`talk` 或 `hfp-mic`。APK 输出到各自的 `build/`。默认安装命令部署桌面和对讲。麦克风 APK 可单独安装，桌面会自动列出「蓝牙麦克风」。桌面和对讲的公共 UI 来自 `config/radio-ui/`，调整后先运行 `make ui-generate`；检查流程见 [UI 契约](../docs/radio-ui-contract.md)。

`data/android/private/` 中的本机签名和对讲私有配置应保留，不得提交。两 APK 同签名，升级使用覆盖安装。[应用边界与 PTT 接口](../docs/architecture.md)。

在 mx_b5 上打开「蓝牙麦克风」，进入「设置 → 蓝牙配对」，在电脑上选手台为输入。返回手台应用，点当前模式按钮，可选普通 PTT、Fn 首尾点按、持续按住 Fn。普通模式按下传音、松开静音。首尾点按模式在首次 Fn 松开后等待 500 毫秒再打开语音。持续模式在关音后释放 Fn。设置保存在手台，每次使用时打开应用即可。

macOS 14.4 对默认 Globe 报告产生短 Fn 脉冲。要让 Fn 持续到松手，连接手台键盘后在 Mac 运行 `python3 android/scripts/mac-ptt-fn.py NeoTalkie-XXXX`，用实际设备名替换参数。脚本保留此设备上其他键的映射，可用 `--remove` 撤销。设备断开再连接或重启后可能需要重新运行。手台上无需 ADB 或 root。
