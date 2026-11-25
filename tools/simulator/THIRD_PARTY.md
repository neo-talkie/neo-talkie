# 本地字体

字体来自 Android Open Source Project 的 `platform/frameworks/base` 仓库，固定修订：
`029b6330380a21787647ed9faa9aa2d6ee93da82`。

- [DroidSansFallback.ttf](https://android.googlesource.com/platform/frameworks/base/+/029b6330380a21787647ed9faa9aa2d6ee93da82/data/fonts/DroidSansFallback.ttf)
  - 原文件 SHA-256：`8fe329bfcab968760be992f1550428821c5b3ac4b246bd60dd8c1f9b7e26e2a0`
- [Roboto-Regular.ttf](https://android.googlesource.com/platform/frameworks/base/+/029b6330380a21787647ed9faa9aa2d6ee93da82/data/fonts/Roboto-Regular.ttf)
  - 原文件 SHA-256：`e7fc7282c9e0d97642126fde016b0a6ce3a2b1b1305913468485824e9a11d6d8`
- [NOTICE](https://android.googlesource.com/platform/frameworks/base/+/029b6330380a21787647ed9faa9aa2d6ee93da82/data/fonts/NOTICE) 原样保存在 `assets/NOTICE`，包含 Apache License 2.0 及版权声明。

浏览器版本为修改后的 WOFF2 文件：用 fontTools 4.60.2 / Brotli 1.2.1 展开 glyf，清除历史字形标记的保留位 7（`flag & 0x7f`），重新序列化并压缩为 WOFF2；保留完整字符集，未改变字形轮廓。这样避免旧 TTF 被现代浏览器 OTS 拒绝。生成文件校验和在 `assets/checksums.json`。

示意机壳和界面图标为本仓库 SVG/Canvas 代码绘制，不包含用户照片、厂家商标或网上下载的机身图片。
