# 开发工具

在工作区的 `neo-talkie-design-studio/` 目录运行 `npm start` 打开手台设计工作台。首页按应用展示设计稿和 Android 实图，右上角可进入交互模拟器。固件使用固定发行副本执行离线校验。

`simulator/` 提供离线手台 UI；`ui/` 生成共享契约、检查 Java/JS 行为并输出截图对照；`scripts/` 检查开源内容。

本目录的 `package.json` 与锁文件只管理这些电脑端工具的依赖，安装到本目录 `node_modules/`。它不管理 Android 的运行依赖。

在固件仓库根目录执行 `make setup` 安装依赖，用 `make browser-setup` 安装截图用 Chromium，再用 `make ui-visual BASE=HEAD` 生成对照。完整流程见 [UI 契约](../docs/radio-ui-contract.md) 和 [模拟器说明](simulator/README.md)。
