# NeoTalkie development

Every commit message and PR/MR description must end with a blank line and:
Co-Authored-By: Riff

## Scope and boundaries

This repository contains device software only. Do not add legacy platform servers, account data, cloud credentials, or recordings. Tests and demos must not contact external services. Hardware and billable voice checks are explicit operator actions, never automatic tests.

| Directory | Responsibility |
| --- | --- |
| `android/launcher/` | Independent `com.neotalkie.launcher` APK: HOME, device/network status, Android Settings root entry, foreground BLE validation and physically approved encrypted Wi-Fi setup, foreground BLE handoff and opt-in, bound-phone background Wi-Fi management, signature-protected PTT handoff provider. |
| `android/talk/` | Independent `com.neotalkie.talk` APK: capture, ASR/AI/TTS, short-term conversation, service diagnostics; cycles configured channels with the lower side key, owns voice and channel configuration, bounded text history, and the signature-protected companion provider. |
| `android/audio-link/` | NeoTalkie Audio Link (音频互联), the optional mx_b5 Bluetooth audio and shortcut APK. Enables the A2DP media sink and HFP hands-free role while visible. Reuses the shared volume strip for media and HFP speaker levels. Owns the HFP audio gate, HID keyboard, ordinary PTT and start/end Fn taps. The pairing button requests a 120-second discoverable window and the system handles bonding. |
| `android/common/` | Compile-time Java UI, power, volume, navigation and PTT contract; no model calls or shared credentials. |
| `tools/simulator/` | Pinned Design Studio distribution for all three apps, with a single-page design catalog, Android capture comparisons and the legacy simulator. Start the workbench with `npm start` in the standalone Design Studio package. Edit rendering there and export the verified release. |
| `config/radio-ui/` | Single source for UI strings, layout, timing and regression scenarios. |
| `android/scripts/` | Independent build, offline tests, dependency setup and explicit ADB actions. |
| `tools/ui/` | UI contract generation, Java/JS parity, screenshot review and drift gates; Node dependencies belong to `tools/package.json`. |
| `tools/scripts/` | Public-source checks; no device or service access. |

The root Makefile coordinates independent projects; the root has no npm package or Node dependencies. Android builds directly with Python/JDK/Android SDK, without Gradle or a Node runtime. Preserve ignored local signing keys/configuration when moving source directories.

Update this index and the relevant architecture documentation in the same change whenever modules, boundaries or interfaces change.

## Delivery

每个自研 APK 的前台窗口须将音量键交给 `android/common/` 的共享音量条。新增 Activity 或单独接收按键的应用内对话框时，须覆盖该窗口的按键分发和音量条显示。同一窗口内切换页面可沿用已有接入。见[手台音量键与音量条](docs/architecture.md#手台音量键与音量条)。

所有手台应用均按无触屏设备设计。首页、菜单、模式选择和应用内对话框的可操作项都须单列纵向排列。上、下键逐项移动可见焦点，确定键执行当前项，返回键返回上级或退出。完整操作路径须仅靠实体按键走通，不得依赖触摸、左右键或横排按钮。

Do not edit generated UI files directly. Regenerate with `make ui-generate`. For UI changes, run `make ui-visual`, inspect screenshots, record review with `make ui-accept REASON="..."`, then pass `make ui-check`. Build both affected APKs.


## Documentation

- [Android build layout](android/README.md) / [Development tools](tools/README.md)
- [Architecture and app boundaries](docs/architecture.md)
- [Device configuration service, per-app forms and background Wi-Fi management design](docs/device-configuration.md)
- [Fresh-device setup, SDK and ADB](docs/getting-started.md)
- [Offline and physical acceptance](docs/verification.md)
- [UI contract and review workflow](docs/radio-ui-contract.md)
- [Simulator](docs/radio-simulator.md) / [scenario catalog](docs/radio-simulator-states.md)

- [BLE protocol and physical acceptance](docs/ble-provisioning.md)
