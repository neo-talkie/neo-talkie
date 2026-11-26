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

在 mx_b5 上打开「蓝牙麦克风」，进入「设置 → 蓝牙配对」，在电脑上选手台为输入。返回手台应用，点当前模式按钮，可选普通 PTT 或 Fn 首尾点按。普通模式按下传音、松开静音。首尾点按模式在首次 Fn 松开后等待 500 毫秒再打开语音。松手时先关闭语音，再点按一次 Fn。设置保存在手台。两种模式均可直接使用 Mac 默认按键配置。
