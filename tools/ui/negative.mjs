// Prove the gates fail on drift in an isolated copy, never editing the worktree.
import {
  mkdtemp,
  mkdir,
  copyFile,
  readFile,
  writeFile,
  rm,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import assert from "node:assert/strict";
const root = fileURLToPath(new URL("../../", import.meta.url)),
  temp = await mkdtemp(join(tmpdir(), "neotalkie-ui-gates-"));
const copy = async (path) => {
  await mkdir(dirname(join(temp, path)), { recursive: true });
  await copyFile(join(root, path), join(temp, path));
};
const run = (file, ...args) =>
  spawnSync(process.execPath, [join(temp, file), ...args], {
    encoding: "utf8",
  });
let passed = 0;
try {
  for (const p of [
    "tools/ui/generate.mjs",
    "config/radio-ui/contract.json",
    "config/radio-ui/scenarios.json",
    "android/common/src/com/neotalkie/device/UiContract.java",
    "tools/simulator/generated/ui-contract.js",
  ])
    await copy(p);
  assert.equal(run("tools/ui/generate.mjs", "--check").status, 0);
  for (const path of [
    "android/common/src/com/neotalkie/device/UiContract.java",
    "tools/simulator/generated/ui-contract.js",
  ]) {
    const original = await readFile(join(temp, path), "utf8");
    await writeFile(join(temp, path), original + "\n// out of sync\n");
    const result = run("tools/ui/generate.mjs", "--check");
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Generated file is stale/);
    passed++;
    await writeFile(join(temp, path), original);
  }
  const path = join(temp, "config/radio-ui/contract.json"),
    raw = await readFile(path, "utf8"),
    c = JSON.parse(raw);
  c.copy.unmapped = "未映射状态";
  c.nativeStates.push("unmapped");
  await writeFile(path, JSON.stringify(c));
  const missing = run("tools/ui/generate.mjs", "--check");
  assert.notEqual(missing.status, 0);
  assert.match(missing.stderr, /Native state lacks a simulator scenario/);
  passed++;
  await writeFile(path, raw);
  for (const p of [
    "tools/ui/guard.mjs",
    "tools/ui/source-hashes.mjs",
    "config/radio-ui/review.json",
  ])
    await copy(p);
  const review = JSON.parse(
    await readFile(join(root, "config/radio-ui/review.json"), "utf8"),
  );
  for (const p of Object.keys(review.hashes)) await copy(p);
  assert.equal(run("tools/ui/guard.mjs").status, 0);
  const native = join(temp, "android/talk/src/com/neotalkie/device/MainActivity.java");
  await writeFile(
    native,
    (await readFile(native, "utf8")) + "\n// UI adapter changed\n",
  );
  const stale = run("tools/ui/guard.mjs");
  assert.notEqual(stale.status, 0);
  assert.match(stale.stderr, /UI adapters changed since/);
  passed++;
  console.log(`UI negative checks: ${passed} intentional drifts rejected`);
} finally {
  await rm(temp, { recursive: true, force: true });
}
