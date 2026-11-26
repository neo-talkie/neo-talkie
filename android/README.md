# Android 工程

本目录交付桌面、对讲和可选的音频互联 APK。三个应用各自拥有 Manifest、资源、源码及测试。`launcher/` 和 `talk/` 共享 `common/` 中的 Java 源码。

当前使用 Python 脚本直接调用 JDK 17 与 Android SDK 构建，没有 Gradle 工程，也不使用 Node 运行时。电脑依赖与首次安装见 [新设备启用](../docs/getting-started.md)。

在仓库根目录执行：

```sh
make android-setup
make android-build
make android-test
make install SERIAL=YOUR_SERIAL
```

只构建一个应用可运行 `python3 android/scripts/build.py launcher`、`talk` 或 `audio-link`。APK 输出到各自的 `build/`。默认安装命令部署桌面和对讲。音频互联 APK 可单独安装，桌面会自动列出「音频互联」。桌面和对讲的公共 UI 来自 `config/radio-ui/`，调整后先运行 `make ui-generate`；检查流程见 [UI 契约](../docs/radio-ui-contract.md)。

`data/android/private/` 中的本机签名和对讲私有配置应保留，不得提交。三个 APK 使用同一份本地签名，升级时覆盖安装。[应用边界与 PTT 接口](../docs/architecture.md)。

### NeoTalkie Audio Link（音频互联）

1. 在 mx_b5 打开「音频互联」，点「蓝牙配对」。在电脑蓝牙中选择首页显示的设备名，核对两端配对码。
2. 在电脑的声音设置或通话应用中，将输入设备选为手台。如果电脑列出同名通话输出，也可以选作输出。回到手台的音频互联主页，按住 PTT 讲话，松开静音。
3. 点当前模式可在「普通 PTT」和「Fn 首尾点按」之间切换。后者先点按 Mac Fn，等待 500 毫秒再传音；松手后关闭语音，再点按一次 Fn。两种模式均使用 Mac 默认键盘配置。
4. 「使用指南」可以随时重看接入步骤。要切回原蓝牙角色，在指南最后一页点「恢复原模式」，再打开「NeoTalkie对讲」。

构建目标为 `audio-link`，产物为 `android/audio-link/build/neotalkie-audio-link.apk`。沿用 Android 安装标识 `com.neotalkie.hfpmic` 和原签名，覆盖安装可保留已选模式。
