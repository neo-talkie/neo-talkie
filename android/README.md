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

1. 打开「音频互联」，用上下键选中「蓝牙配对」，按确定。允许手台临时可被发现，在电脑蓝牙列表中选择首页显示的设备名并核对配对码。
2. 在电脑声音设置中选手台作为输入。手台侧面 PTT 按住传音，松开静音。
3. 向下选择「模式」，按确定进入，两种模式上下排列。普通 PTT 直接传音；Fn 模式先点按 Mac Fn，等半秒传音，松手后结束传音并再次点按 Fn。
4. 返回键从模式页回到首页，在首页再按返回即退出。回到桌面或切到其他应用也会关闭音频互联，自动恢复原蓝牙角色，可直接打开「NeoTalkie对讲」。

构建目标为 `audio-link`，产物为 `android/audio-link/build/neotalkie-audio-link.apk`。沿用 Android 安装标识 `com.neotalkie.hfpmic` 和原签名，覆盖安装可保留已选模式。
