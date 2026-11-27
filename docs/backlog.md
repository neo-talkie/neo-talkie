# NeoTalkie Backlog

记录已确认、计划在后续迭代中推进的能力与优化项。

## OTA 与发布

- [ ] **购买并配置 `neo-talkie.com` 域名**：后续购买 `neo-talkie.com` 并在 Cloudflare 配置 `https://ota.neo-talkie.com/v1/ota-manifest.json` 的 302 重定向（指向阿里云 OSS 或后续迁移的新存储清单地址），激活手台已预埋的二级自有域名 OTA 容灾入口。
- [ ] **OTA 下载链接域名转发与地址替换**：接入自定义转发域名，支持将 GitHub Release 的 `ota-manifest.json` 与 APK 下载链接替换为自定义域名地址，以改善国内网络环境下的下载速度与稳定性。
  - 当前已在设备端 `OtaManifest.resolveUrl()` 与 `OtaUpdater` 中预留 `manifestUrl` / `mirrorPrefix` 替换钩子，后续补充转发域名配置及（可选的）小程序端远程配置入口。
