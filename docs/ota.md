# OTA 静默更新与发布流水线

NeoTalkie 在桌面的 **「设备设置 → 软件更新」** 中提供整机全量应用 OTA 检查、下载、SHA-256 校验与静默安装能力。

## 整体机制

1. **统一发布清单（`ota-manifest.json`）**
   - `main` 分支每次推送后，由 [`.github/workflows/release.yml`](../.github/workflows/release.yml) 自动构建所有应用 APK，并由 [`android/scripts/release_manifest.py`](../android/scripts/release_manifest.py) 生成 `ota-manifest.json`（`schema: "neotalkie-ota/v1"`）一并上传至 GitHub Releases，并标记为 `latest`。
   - 清单包含全部应用的 `packageName`、`versionCode`、`versionName`、`url`、`sha256`、`size` 和 `self` 标记。未来在 `android/scripts/build.py` 的 `APPS` 列表中新增应用时，流水线会自动打包并纳入清单，手台侧也会自动识别并安装新应用。
   - 手台默认拉取 `https://github.com/neo-talkie/neo-talkie/releases/latest/download/ota-manifest.json`（不依赖有频率限制的 GitHub REST API）。

2. **Android 5.1.1 HTTPS 兼容与完整性校验**
   - `mx_b5`（Android 5.1.1）系统根证书包含 `github.com` 所用的 `USERTrust ECC`，但不包含 GitHub Release 文件跳转域名（`release-assets.githubusercontent.com` / `objects.githubusercontent.com`）所用的 `ISRG Root X1`。
   - 桌面应用的 `OtaTls` 内置公开 `ISRG Root X1` 根证书并与系统默认信任库合并，同时强制启用 TLS 1.2 并拒绝跳往非 HTTPS 远端地址。
   - 所有待更新 APK 在进入安装阶段前全部完成下载、字节数比对和 SHA-256 校验，避免半更新状态。

3. **静默安装权限与 Android 版本差异（已在 `mx_b5` 实机验证）**
   - 桌面应用同时支持 Android 官方的两种免弹窗静默安装授权路径，均通过 `PackageInstaller` 会话流式写入并静默提交：
     - **Android 5.1（如当前主力机型 `mx_b5`，API 22）**：AOSP 5.1 的 `PackageInstaller` 仅向持有 `android.permission.INSTALL_PACKAGES` 的系统特权应用开放免弹窗静默安装（`dpm set-device-owner` 虽然可激活，但 5.1 上仍会返回 `STATUS_PENDING_USER_ACTION`）。因此 `mx_b5` 上启用真正无弹窗静默安装时，需在出厂/刷机镜像中将 `neotalkie-launcher.apk` 预置到 `/system/priv-app/`（与原厂 `/system/priv-app/Launcher.apk` 相同位置），系统会自动授予已声明的 `INSTALL_PACKAGES` 权限。
     - **Android 6.0+（API 23+）机型**：无需放进 `/system/priv-app/`，普通安装后由 `android/scripts/device.py` 执行一次：
       ```sh
       adb -s YOUR_SERIAL shell dpm set-device-owner com.neotalkie.launcher/com.neotalkie.device.OtaAdminReceiver
       ```
       即可通过 Device Owner 获得免弹窗静默安装能力。
   - **安装顺序**：总是先安装其余应用（对讲、音频互联及未来新应用），最后安装桌面（`com.neotalkie.launcher`）自身；桌面替换重启后由 `ManagementService$Restore` 清理临时文件并显示「更新完成」。

4. **本地调试版与线上 OTA 版区分规则**
   - **本地调试版**（`make android-build`，未传 `--build-number`）：保留 `AndroidManifest.xml` 原 `versionCode`（`1 ~ 9999`），`versionName` 自动带 `-dev` 后缀（如 `0.2.3-dev`）。
   - **线上 OTA 版**（CI 发布，传入 `NEOTALKIE_RELEASE_BUILD=<run_number>`）：`versionCode = 原值 × 10000 + run_number`（如 `70001`），`versionName` 自动带 `+<run_number>` 后缀（如 `0.2.3+1`）。
   - 本地调试即便抬高 `AndroidManifest.xml` 的 `versionCode`，只要未进入万位区间仍小于线上 OTA，可随时检测并拉取线上更新；`device.py install` 默认携带 `adb install -r -d`，也支持从线上 OTA 一键降级刷回本地 `-dev` 调试包。

## GitHub Release 签名配置（首次启用必做）

Android 要求覆盖升级的 APK 必须与设备上已安装版本**签名完全一致**。为了防止误发布随机临时签名包，发布流水线要求在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 中配置以下 Secrets：

| Secret 名称 | 说明 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 固定 Release Keystore 的 Base64 编码（无换行） |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore 密码 |
| `ANDROID_KEY_ALIAS` | 签名密钥别名 |
| `ANDROID_KEY_PASSWORD` | 密钥密码（与 Keystore 密码相同时可省略） |

生成或复用现有本地 Keystore 并写入 GitHub Secrets 示例：

```sh
# 方式 A：复用当前已刷入真机的本地签名（已刷机设备可直接无缝 OTA）
base64 -i data/android/private/debug.keystore | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64
gh secret set ANDROID_KEYSTORE_PASSWORD --body "android"
gh secret set ANDROID_KEY_ALIAS --body "androiddebugkey"
gh secret set ANDROID_KEY_PASSWORD --body "android"

# 方式 B：生成全新长期 Release Keystore（新机首次刷机时通过 NEOTALKIE_KEYSTORE_PATH 使用同一把密钥）
keytool -genkeypair -keystore release.keystore -storepass "<PASS>" -keypass "<PASS>" \
  -alias neotalkie -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=NeoTalkie Release,O=NeoTalkie,C=CN"
```

本地用同一把签名构建并刷入新机：

```sh
NEOTALKIE_KEYSTORE_PATH=/path/to/release.keystore \
NEOTALKIE_KEYSTORE_PASSWORD="<PASS>" \
NEOTALKIE_KEY_ALIAS="neotalkie" \
NEOTALKIE_KEY_PASSWORD="<PASS>" \
make android-build

python3 android/scripts/device.py --serial YOUR_SERIAL install
```

## 后续规划

见 [项目 Backlog](backlog.md)（包含自定义域名转发与下载链接替换能力）。
