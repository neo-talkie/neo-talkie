# 新设备启用

## 电脑准备

需要 Node.js 24.11+、Python 3、JDK 17、Android SDK Command-line Tools 和 Platform Tools。以下为 macOS/Linux shell；JDK/SDK 可用 Android Studio 或系统包管理器安装。

```sh
# 指向自己的 SDK 和 JDK；不要使用另一项目的私有配置。
export NEOTALKIE_ANDROID_SDK="$HOME/Library/Android/sdk"
export NEOTALKIE_JAVA_HOME="$(/usr/libexec/java_home -v 17)"  # macOS；Linux 设置为自己的 JDK 17 路径
export PATH="$NEOTALKIE_ANDROID_SDK/cmdline-tools/latest/bin:$NEOTALKIE_ANDROID_SDK/platform-tools:$PATH"
sdkmanager 'platforms;android-28' 'build-tools;35.0.0' 'platform-tools'
make setup
python3 android/scripts/setup.py
make ui-generate
make android-build
```

`setup.py` 从 Maven 下载两个固定版本依赖并验证 SHA-256。构建不访问网络；首次构建在忽略目录 `data/android/private/debug.keystore` 生成签名，两个 APK 共用。保留本机签名以便覆盖升级。默认 JDK/SDK 也支持 `.tools/android/`，环境变量可显式覆盖。

## 连接和安装

手台开启开发者选项、USB 调试并授权电脑。目标 mx_b5 曾需要 USB-A → USB-C 数据线；其他机型先确认数据连接。运行 `adb devices -l`，只使用状态为 device 的序列号。

```sh
python3 android/scripts/device.py --serial YOUR_SERIAL doctor
python3 android/scripts/device.py --serial YOUR_SERIAL install
python3 android/scripts/device.py --serial YOUR_SERIAL home
```

两个包名为 `com.neotalkie.launcher` 和 `com.neotalkie.talk`。安装不会卸载其他应用。按 HOME，在系统选择器将 NeoTalkie 桌面设为“始终”；若已设其他默认桌面，先在系统设置清除其默认值。先确认新桌面能稳定启动，再考虑人工处理厂商桌面；部分固件的厂商桌面硬编码依赖原配应用。

桌面可打开对讲、设备设置、系统设置和手机配置；上下移动，MENU 确认。设备设置显示 NeoTalkie 的网络和系统状态；系统设置打开 Android 设置首页，可继续访问 Wi-Fi、蓝牙及其他 ROM 设置。推荐使用「手机配置」由微信小程序连接当前 Wi-Fi 或加密下发新的 Wi-Fi 配置，然后填写模型、密钥和频道，具体步骤见 [BLE 配网](ble-provisioning.md)。系统设置在 160×128 上的可操作性需针对新固件实测。

## 配置语音

在已忽略的 `data/private/` 下创建配置：

```sh
mkdir -p data/private
cp config/device.example.json data/private/device.json
chmod 600 data/private/device.json
# 用本机编辑器填写 voiceApiKey、arkApiKey、model（已开通的模型/endpoint ID）和可选音色。
python3 android/scripts/provision.py --serial YOUR_SERIAL --config data/private/device.json
```

脚本安装对讲并通过标准输入传入配置，核对传输结果后设置私有文件权限，不打印密钥。打开对讲不自动发起模型请求；用户按住 PTT 才开始真实语音交互，菜单中的语音服务检查也只在主动选择后发起请求。

## 先做本地验收

```sh
python3 android/scripts/device.py --serial YOUR_SERIAL status
python3 android/scripts/device-command.py --serial YOUR_SERIAL ptt-wake-check
```

`ptt-wake-check` 在本进程开启只采集/丢弃音频的离线模式，不发起 ASR/TTS。进入桌面后按 PTT，验证跨 APK 冷/热启动、松手、重复事件、40 秒息屏后的唤醒；结束后强停对讲进程以退出离线模式。`local-audio` 等其他动作必须由操作者明确执行；`live-pcm` 会使用配置的外部服务，禁止加入自动测试。
