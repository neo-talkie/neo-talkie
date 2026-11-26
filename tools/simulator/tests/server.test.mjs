import { ANDROID_BASELINE } from "../generated/ui-contract.js";
import test from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { readFile } from "node:fs/promises";

test("static server stays local, serves bundled fonts, and rejects writes/traversal", async (t) => {
  const child = spawn(
    process.execPath,
    [new URL("../server.mjs", import.meta.url).pathname],
    {
      env: { ...process.env, RADIO_SIM_PORT: "0" },
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  t.after(() => child.kill());
  const [data] = await once(child.stdout, "data");
  const origin = String(data).match(/http:\/\/127\.0\.0\.1:\d+/)?.[0];
  assert.ok(origin);
  const index = await fetch(origin);
  assert.equal(index.status, 200);
  assert.match(
    index.headers.get("content-security-policy"),
    /connect-src 'self'/,
  );
  assert.match(await index.text(), /手台设计工作台/);
  assert.match(await (await fetch(origin + "/lab")).text(), /手台界面实验室/);
  const font = await fetch(origin + "/assets/DroidSansFallback.woff2");
  assert.equal(font.status, 200);
  assert.equal(font.headers.get("content-type"), "font/woff2");
  assert.ok((await font.arrayBuffer()).byteLength > 1000000);
  for (const path of [
    "/../AGENTS.md",
    "/%2e%2e%2fpackage.json",
    "/.env",
    "/server.mjs",
    "/tests/model.test.mjs",
  ])
    assert.equal((await fetch(origin + path)).status, 404, path);
  assert.equal(
    (await fetch(origin, { method: "POST", body: "no writes" })).status,
    405,
  );
});

test("Android source baseline has not drifted from simulator layout contract", async () => {
  const root = new URL("../../../android/talk/", import.meta.url);
  const manifest = await readFile(new URL("AndroidManifest.xml", root), "utf8");
  assert.equal(manifest.match(/versionName="([^"]+)"/)[1], ANDROID_BASELINE);
  const main = await readFile(
    new URL("src/com/neotalkie/device/MainActivity.java", root),
    "utf8",
  );
  assert.match(
    main,
    /LEFT_EDGE = UiContract.LEFT, CONTENT_X = UiContract.CONTENT_X, CONTENT_WIDTH = UiContract.CONTENT_WIDTH/,
  );
  assert.match(main, /UiContract.TEXT_BASELINE \+ row \* UiContract.LINE_HEIGHT, UiContract.FONT_BODY/);
  assert.match(main, /postDelayed\(finishTextBrowsing, UiContract.REVIEW_HOLD_MS\)/);
  const volume = await readFile(
    new URL("../../../android/common/src/com/neotalkie/device/VolumeStrip.java", import.meta.url),
    "utf8",
  );
  assert.match(volume, /drawRect\(0, UiContract.FOOTER_TOP, UiContract.WIDTH, UiContract.HEIGHT/);
  const feedback = await readFile(
    new URL("../../../android/common/src/com/neotalkie/device/RecordingFeedback.java", import.meta.url),
    "utf8",
  );
  assert.match(feedback, /MAX_SECONDS = UiContract.CAPTURE_MS \/ 1000/);
});
