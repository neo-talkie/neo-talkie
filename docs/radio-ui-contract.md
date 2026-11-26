# UI 一致性

`config/radio-ui/contract.json` 保存系统文案、两应用菜单、布局、颜色、计时和图形；`scenarios.json` 保存离线场景。`tools/ui/generate.mjs` 生成共享 Java UiContract 和浏览器模块，禁止直接修改生成文件。共享源码位于 `android/common/`。

工作台的单页目录同时覆盖桌面、对讲和音频互联。在独立的 `neo-talkie-design-studio/` 目录运行 `npm start`，首页显示设计基准与相同场景的 Android 实图，`/lab` 打开原模拟器。实图可在浏览器中按场景导入，命令行导入目录默认为工作台的 `data/captures/`。两种图片分别记录来源；像素回归图册由浏览器产生。

修改固件契约后先生成 Java 和浏览器模块，再从独立工作台运行 `node scripts/firmware-release.mjs contract ../neo-talkie`。更新和复核设计基准、运行工作台测试后，使用 `export ../neo-talkie` 发布固定副本。`make ui-check` 校验发行摘要及每个场景的输入指纹。Android 源码复核也覆盖音频互联。

桌面网络页只读本机状态；对讲网络测试页执行由用户触发的服务诊断。模拟器用 owner 区分应用，包含桌面设置、系统状态、网络场景。

```sh
make ui-generate
npm --prefix tools run simulator:test
npm --prefix tools run ui:parity
make browser-setup
make ui-visual
# 打开 data/ui-regression/latest/index.html 和 visual-diff.html 检查
make ui-accept REASON='说明变更以及原生/浏览器核对结果'
make ui-check
```

首次独立仓库没有基准提交，出图生成初始图册；后续可 `make ui-visual BASE=HEAD`。回归记录包含当前源码指纹，改动后旧截图不可用于复核。负向测试验证手改生成物、遗漏场景和未复核适配改动均失败。

浏览器测试拦截外部请求，只使用本机字体和固定数据。截图属于浏览器渲染，不冒充 Android 真机截图；真实按键、广播时序、硬件音量、系统 Wi-Fi 界面、字体和息屏需要物理验收。
