# 开发工具

`simulator/` 提供离线手台 UI；`ui/` 生成共享契约、检查 Java/JS 行为并输出截图对照；`scripts/` 检查开源内容。

本目录的 `package.json` 与锁文件只管理这些电脑端工具的依赖，安装到本目录 `node_modules/`。它不管理 Android 的运行依赖。

在仓库根目录使用 `make setup` 安装，`make simulator` 启动本地模拟器；`make browser-setup` 安装截图用 Chromium，`make ui-visual BASE=HEAD` 生成对照。完整流程见 [UI 契约](../docs/radio-ui-contract.md) 和 [模拟器说明](simulator/README.md)。
