// Deterministic, source-controlled inventory. Run from any working directory.
import { writeFile, readFile } from "node:fs/promises";
import { ANDROID_BASELINE } from "../generated/ui-contract.js";
import { PRESETS } from "../model.js";
const lines = [
  "# 手台 UI 回归场景清单",
  "",
  "由 `node tools/simulator/scripts/catalog.mjs` 从预设生成。编号与总览 PNG、JSON 参数一致；稳定 ID 用于跨版本对照。",
  "",
  "当前基准：Android " + ANDROID_BASELINE + "。共 " +
    PRESETS.length +
    " 个命名场景；这是应用页面和代表性边界覆盖，不是所有数值组合，也不覆盖厂家 ROM 界面。参数和使用方法见 [模拟器文档](radio-simulator.md)。",
  "",
];
for (const group of new Set(PRESETS.map((p) => p.group))) {
  lines.push(
    "## " + group,
    "",
    "| 编号 | 稳定 ID | 场景 | 检查重点 |",
    "| --- | --- | --- | --- |",
  );
  for (const [i, p] of PRESETS.entries())
    if (p.group === group)
      lines.push(
        `| ${String(i + 1).padStart(3, "0")} | ${p.id} | ${p.name} | ${p.note} |`,
      );
  lines.push("");
}
const path = new URL(
    "../../../docs/radio-simulator-states.md",
    import.meta.url,
  ),
  output = lines.join("\n");
if (process.argv.includes("--check")) {
  if ((await readFile(path, "utf8")) !== output)
    throw new Error("状态清单已过期，请运行 scripts/catalog.mjs");
} else await writeFile(path, output);
