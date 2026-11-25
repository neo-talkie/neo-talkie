import { sourceHashes } from "./source-hashes.mjs";
import { chromium } from "playwright";
import { PNG } from "pngjs";
import pixelmatch from "pixelmatch";
import { createServer } from "node:http";
import {
  readFile,
  writeFile,
  mkdir,
  mkdtemp,
  rm,
  stat,
} from "node:fs/promises";
import { resolve, join, extname } from "node:path";
import { tmpdir } from "node:os";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
const root = fileURLToPath(new URL("../../", import.meta.url));
const args = process.argv.slice(2),
  option = (name, fallback) =>
    args.includes(name) ? args[args.indexOf(name) + 1] : fallback;
const out = resolve(
  option(
    "--output",
    process.env.UI_REPORT_DIR || join(root, "data/ui-regression"),
  ),
);
const baseRef = option("--base", process.env.UI_BASE_REF || "");
const resources = [],
  servers = [];
let browser, temp;
const esc = (s) =>
  String(s).replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
async function serve(dir) {
  const server = createServer(async (req, res) => {
    try {
      const name = decodeURIComponent(
        new URL(req.url, "http://localhost").pathname,
      );
      const file = resolve(dir, "." + (name === "/" ? "/index.html" : name));
      if (!file.startsWith(dir + "/") || req.method !== "GET") throw Error();
      const types = {
        ".js": "text/javascript",
        ".html": "text/html",
        ".css": "text/css",
        ".woff2": "font/woff2",
        ".ttf": "font/ttf",
        ".svg": "image/svg+xml",
      };
      if (!types[extname(file)]) throw Error();
      res.writeHead(200, {
        "Content-Type": types[extname(file)],
        "Cache-Control": "no-store",
      });
      res.end(await readFile(file));
    } catch {
      res.writeHead(404);
      res.end();
    }
  });
  await new Promise((r) => server.listen(0, "127.0.0.1", r));
  servers.push(server);
  return `http://127.0.0.1:${server.address().port}`;
}
async function open(dir) {
  const origin = await serve(dir),
    page = await browser.newPage({
      viewport: { width: 1440, height: 1000 },
      deviceScaleFactor: 1,
    });
  await page.route("**/*", (route) => {
    const u = route.request().url();
    if (u.startsWith(origin + "/")) return route.continue();
    resources.push(u);
    return route.abort();
  });
  await page.goto(origin);
  await page.waitForFunction(() => !!window.radioSimulator);
  await page.evaluate(() => document.fonts.ready);
  return page;
}
async function screens(page) {
  return page.evaluate(async () => {
    const { PRESETS, preset } = await import("/model.js");
    const { renderScreen } = await import("/render.js");
    return PRESETS.map((p) => {
      const c = document.createElement("canvas");
      renderScreen(c, preset(p.id), 0);
      return { id: p.id, name: p.name, png: c.toDataURL().split(",")[1] };
    });
  });
}
try {
  await mkdir(out, { recursive: true });
  browser = await chromium.launch({ headless: true });
  const page = await open(join(root, "tools/simulator"));
  const checks = await page.evaluate(
    "(" +
      (await readFile(
        join(root, "tools/simulator/tests/browser-check.js"),
        "utf8",
      )) +
      ")()",
  );
  if (!checks.every((c) => c.pass)) throw Error("Browser UI checks failed");
  await writeFile(
    join(out, "browser-check.json"),
    JSON.stringify(checks, null, 2),
  );
  const head = await screens(page),
    again = await screens(page);
  if (JSON.stringify(head) !== JSON.stringify(again))
    throw Error("Frozen screenshots are not deterministic");
  const bundle = await page.evaluate(
    "(" +
      (await readFile(
        join(root, "tools/simulator/tests/export-gallery.js"),
        "utf8",
      )) +
      ")()",
  );
  await writeFile(join(out, "gallery-bundle.json"), JSON.stringify(bundle));
  execFileSync(
    process.env.PYTHON || "python3",
    [
      join(root, "tools/simulator/scripts/unpack-gallery.py"),
      join(out, "gallery-bundle.json"),
      out,
    ],
    { stdio: "inherit" },
  );
  let base = [],
    baseCommit = null;
  if (baseRef && !/^0+$/.test(baseRef)) {
    baseCommit = execFileSync(
      "git",
      ["rev-parse", "--verify", baseRef + "^{commit}"],
      { cwd: root, encoding: "utf8" },
    ).trim();
    temp = await mkdtemp(join(tmpdir(), "neotalkie-ui-base-"));
    let baseDirectory="tools/simulator";
    try { execFileSync("git",["cat-file","-e",baseCommit+":"+baseDirectory+"/model.js"],{cwd:root,stdio:"ignore"}); }
    catch { baseDirectory="apps/radio-simulator"; }
    const archive = execFileSync(
      "git",
      ["archive", baseCommit, baseDirectory],
      { cwd: root, maxBuffer: 30 * 1024 * 1024 },
    );
    execFileSync("tar", ["-x", "-C", temp], { input: archive });
    base = await screens(await open(join(temp, baseDirectory)));
  }
  const byId = new Map(base.map((s) => [s.id, s])),
    report = {
      sourceHashes: await sourceHashes(),
      base: baseCommit,
      head: process.env.UI_HEAD || "working-tree",
      browser: browser.version(),
      platform: process.platform,
      checks: checks.length,
      scenes: head.length,
      changed: [],
      added: [],
      removed: base
        .filter((s) => !head.some((h) => h.id === s.id))
        .map((s) => s.id),
    };
  const cards = [];
  await mkdir(join(out, "diff"), { recursive: true });
  for (const [index, h] of head.entries()) {
    const stem = String(index + 1).padStart(3, "0"),
      old = byId.get(h.id),
      newPng = Buffer.from(h.png, "base64");
    await writeFile(join(out, "diff", stem + "-after.png"), newPng);
    if (!old) {
      report.added.push(h.id);
      cards.push(
        `<article><h2>${esc(h.id)} · 新增</h2><img src="diff/${stem}-after.png"></article>`,
      );
      continue;
    }
    const oldPng = Buffer.from(old.png, "base64"),
      a = PNG.sync.read(oldPng),
      b = PNG.sync.read(newPng),
      diff = new PNG({ width: 160, height: 128 });
    const count = pixelmatch(a.data, b.data, diff.data, 160, 128, {
      threshold: 0,
      includeAA: true,
    });
    if (count) {
      report.changed.push({ id: h.id, pixels: count });
      await writeFile(join(out, "diff", stem + "-before.png"), oldPng);
      await writeFile(
        join(out, "diff", stem + "-delta.png"),
        PNG.sync.write(diff),
      );
      cards.push(
        `<article><h2>${esc(h.id)} · ${count} 像素变化</h2><div><img src="diff/${stem}-before.png"><img src="diff/${stem}-after.png"><img src="diff/${stem}-delta.png"></div><p>修改前 / 修改后 / 差异</p></article>`,
      );
    }
  }
  if (resources.length)
    throw Error("External browser requests blocked: " + resources.join(", "));
  await writeFile(
    join(out, "visual-diff.json"),
    JSON.stringify(report, null, 2),
  );
  await writeFile(
    join(out, "visual-diff.html"),
    `<!doctype html><meta charset="utf-8"><title>NeoTalkie UI 差异</title><style>body{font:16px sans-serif;background:#eff3ef;margin:24px}article{padding:12px;background:white;margin:12px 0}img{width:320px;height:256px;image-rendering:pixelated;margin:8px}h2{font-size:18px}</style><h1>UI 修改前后对照</h1><p>同一浏览器绘制两个 Git 版本，排除跨操作系统差异；不是 Android 真机截图。</p><p>${esc(baseCommit || "未指定基准")} → 当前工作区。变化 ${report.changed.length} / 新增 ${report.added.length} / 删除 ${report.removed.length}。</p><a href="index.html">全部界面</a>${cards.join("")}`,
  );
  console.log(
    JSON.stringify({
      checks: report.checks,
      scenes: report.scenes,
      changed: report.changed.length,
      added: report.added.length,
      removed: report.removed.length,
      output: out,
    }),
  );
} finally {
  await browser?.close();
  for (const s of servers) await new Promise((r) => s.close(r));
  if (temp) await rm(temp, { recursive: true, force: true });
}
