# NeoTalkie development

Every commit message and PR/MR description must end with a blank line and:
Co-Authored-By: Riff

## Scope and boundaries

This repository contains device software only. Do not add legacy platform servers, account data, cloud credentials, or recordings. Tests and demos must not contact external services. Hardware and billable voice checks are explicit operator actions, never automatic tests.

| Directory | Responsibility |
| --- | --- |
| `android/launcher/` | Independent `com.neotalkie.launcher` APK: HOME, device/network status, Android Settings root entry, foreground BLE validation and physically approved encrypted Wi-Fi setup, signature-protected PTT handoff provider. |
| `android/talk/` | Independent `com.neotalkie.talk` APK: capture, ASR/AI/TTS, short-term conversation, service diagnostics; owns voice configuration. |
| `android/common/` | Compile-time Java UI, power, volume, navigation and PTT contract; no model calls or shared credentials. |
| `tools/simulator/` | Offline UI model, local rendering, scenario catalog and screenshot export for both APKs. |
| `config/radio-ui/` | Single source for UI strings, layout, timing and regression scenarios. |
| `android/scripts/` | Independent build, offline tests, dependency setup and explicit ADB actions. |
| `tools/ui/` | UI contract generation, Java/JS parity, screenshot review and drift gates; Node dependencies belong to `tools/package.json`. |
| `tools/scripts/` | Public-source checks; no device or service access. |

The root Makefile coordinates independent projects; the root has no npm package or Node dependencies. Android builds directly with Python/JDK/Android SDK, without Gradle or a Node runtime. Preserve ignored local signing keys/configuration when moving source directories.

Update this index and the relevant architecture documentation in the same change whenever modules, boundaries or interfaces change.

## Delivery

Do not edit generated UI files directly. Regenerate with `make ui-generate`. For UI changes, run `make ui-visual`, inspect screenshots, record review with `make ui-accept REASON="..."`, then pass `make ui-check`. Build both affected APKs.


## Documentation

- [Android build layout](android/README.md) / [Development tools](tools/README.md)
- [Architecture and app boundaries](docs/architecture.md)
- [Fresh-device setup, SDK and ADB](docs/getting-started.md)
- [Offline and physical acceptance](docs/verification.md)
- [UI contract and review workflow](docs/radio-ui-contract.md)
- [Simulator](docs/radio-simulator.md) / [scenario catalog](docs/radio-simulator-states.md)

- [BLE protocol and physical acceptance](docs/ble-provisioning.md)
