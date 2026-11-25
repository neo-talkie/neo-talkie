import { NATIVE_STATES, T } from "../generated/ui-contract.js";
import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import {
  PRESETS,
  PHASES,
  MENU,
  preset,
  createState,
  act,
  tick,
  title,
  snapshot,
  restoreSnapshot,
} from "../model.js";
import { pagesFor, SERVICES, networkType } from "../pages.js";
const source = async (name) => {
  for (const module of ['android/talk', 'android/launcher', 'android/common']) {
    try { return await readFile(new URL(`../../../${module}/src/com/neotalkie/device/${name}.java`, import.meta.url), 'utf8'); }
    catch (e) { if (e.code !== 'ENOENT') throw e; }
  }
  throw Error('Missing native source: ' + name);
};
test("catalog has unique IDs, every conversation phase, and lossless parameter snapshots", () => {
  assert.equal(new Set(PRESETS.map((p) => p.id)).size, PRESETS.length);
  for (const phase of Object.keys(PHASES))
    assert.ok(
      PRESETS.some((p) => p.phase === phase),
      phase,
    );
  for (const p of PRESETS) {
    for (const field of ["name", "group", "note"])
      assert.ok(p[field], p.id + "/" + field);
    const s = preset(p.id);
    assert.deepEqual(restoreSnapshot(snapshot(s)), s, p.id);
  }
});
test("native menus, PTT routing and services consume shared definitions", async () => {
  assert.match(await source("StatusActivity"), /UiContract.menuSelection/);
  assert.match(await source("ServiceChecks"), /NAMES = UiContract.SERVICES/);
  for (const name of [
    "MainActivity",
    "StatusActivity",
    "HomeActivity",
    "BleSetupActivity",
  ])
    assert.match(await source(name), /PttNavigation.key\(this,/);
  for (let selected = 0; selected < MENU.length; selected++)
    assert.ok(
      PRESETS.some((p) => p.view === "menu" && p.selected === selected),
    );
});
test("UI adapters cannot introduce private display literals outside the shared contract", async () => {
  for (const name of [
    "MainActivity",
    "StatusActivity",
    "HomeActivity",
    "BleSetupActivity",
    "StatusStrip",
    "ServiceChecks",
    "ServiceCheckResult",
    "TurnText",
    "VolumeStrip",
  ]) {
    const s = await source(name);
    const literals = [...s.matchAll(/"([^"\n]*)"/g)]
      .map((m) => m[1])
      .filter((v) => /[\u4e00-\u9fff]/.test(v));
    assert.deepEqual(
      literals,
      [],
      name + " contains non-contract display copy",
    );
  }
});
test("MENU opens one level, selection clamps, details return to the same menu row", () => {
  let s = createState({ hasContext: true });
  s = act(s, "confirm", 100);
  assert.equal(s.view, "menu");
  s = act(s, "up");
  assert.equal(s.selected, 0);
  s = act(s, "down");
  s = act(s, "down");
  assert.equal(s.selected, 1);
  s = act(s, "confirm", 600);
  assert.equal(s.view, "device");
  s = act(s, "up");
  assert.equal(s.page, 3);
  s = act(s, "down");
  assert.equal(s.page, 0);
  assert.equal(act(s, "confirm").view, "device");
  s = act(s, "back");
  assert.equal(s.view, "menu");
  assert.equal(s.selected, 1);
  s = act(s, "back");
  assert.equal(s.view, "main");
  assert.equal(title(s), "继续追问");
});
test("PTT returns from every app page, cancels page work and accepts release on the main page", () => {
  for (const view of [
    "menu",
    "device",
    "network",
    "diagnostics",
    "results",
    "checkError",
    "home",
    "ble",
  ]) {
    let s = createState({
      view,
      hasContext: true,
      stageAt: 1800,
    });
    s = act(s, "ptt-down", 100);
    assert.equal(s.view, "main", view);
    assert.equal(s.phase, "listening", view);
    assert.equal(s.stageAt, 0);
    assert.equal(s.hasContext, true);
    s = act(s, "ptt-up", 500);
    assert.equal(s.held, false);
    assert.equal(s.phase, "tooShort");
    assert.equal(tick(s, 10000).view, "main");
    const offline = act(createState({ view, wifi: -1 }), "ptt-down");
    assert.equal(offline.view, "main");
    assert.equal(offline.held, false);
  }
});
test("network and device page counts adapt to actual parameter availability", () => {
  for (const type of ["offline", "mobile", "other"])
    assert.equal(
      pagesFor(createState({ view: "network", network: { type } })).length,
      1,
    );
  assert.equal(pagesFor(createState({ view: "network" })).length, 3);
  assert.equal(
    pagesFor(createState({ view: "network", network: { wifiInfo: false } }))
      .length,
    1,
  );
  assert.equal(
    pagesFor(createState({ view: "device", device: { batteryPresent: false } }))
      .length,
    3,
  );
  assert.equal(
    pagesFor(createState({ view: "results", results: [] })).length,
    1,
  );
  assert.equal(pagesFor(createState({ view: "results" })).length, 4);
  assert.equal(networkType(createState({ wifi: -1 })), "offline");
  assert.equal(
    act(createState({ wifi: -1, network: { type: "mobile" } }), "ptt-down")
      .phase,
    "listening",
  );
});
test("network test checks every service, allows paging during work, and cancels on EXIT", () => {
  let s = act(createState({ view: "menu" }), "confirm", 100);
  assert.equal(s.checking, SERVICES[0]);
  assert.equal(pagesFor(s).length, 4);
  s = act(s, "down", 200);
  assert.equal(s.page, 1);
  assert.equal(act(s, "confirm").page, 1);
  const cancelled = act(s, "back", 300);
  assert.equal(tick(cancelled, 10000).view, "menu");
  assert.equal(tick(cancelled, 10000).results.length, 0);
  s = tick(s, 1900);
  s = tick(s, 3700);
  s = tick(s, 5500);
  assert.equal(s.view, "diagnostics");
  assert.equal(s.page, 1);
  assert.equal(s.results.length, 3);
  assert.equal(pagesFor(s).length, 7);
  s = act({ ...s, page: 0 }, "up");
  assert.equal(s.page, 6);
});
test("desktop opens NeoTalkie on PTT down; external apps have an explicit boundary", () => {
  let s = preset("home");
  assert.equal(act(s, "ptt-down").view, "main");
  assert.equal(act(s, "ptt-up").view, "home");
  assert.equal(act(s, "back").view, "home");
  s = act(act(s, "up"), "up");
  assert.equal(s.selected, 2);
  s = act(s, "confirm");
  assert.equal(s.view, "external");
  assert.equal(act(s, "back").view, "home");
});

test("native assignments and callbacks cannot use an unregistered state", async () => {
  const main = await source("MainActivity"),
    engine = await source("SpeechEngine");
  assert.match(main, /UiContract.requireState\(s\)/);
  const allowed = new Set(NATIVE_STATES),
    byConstant = Object.fromEntries(
      Object.entries(T).map(([k, v]) => ["T_" + k.toUpperCase(), v]),
    );
  for (const match of [
    ...main.matchAll(/state = ([^;,\n]+)/g),
    ...engine.matchAll(/listener.status\(t, ([^,\n]+)/g),
  ]) {
    for (const token of match[1].matchAll(/UiContract\.(T_[A-Z_]+)/g))
      assert.ok(allowed.has(byConstant[token[1]]), token[1]);
  }
});

test("main arrows and default channel cannot leave conversation or adjust volume", () => {
  let s = createState({ volume: 2, hasContext: true });
  for (const key of ["up", "down", "more"]) {
    s = act(s, key, 100);
    assert.equal(s.view, "main");
    assert.equal(s.volume, 2);
    assert.equal(s.hasContext, true);
  }
  assert.equal(tick(s, 40099).view, "main");
  assert.equal(tick(s, 40100).view, "sleep");
  s = act(s, "volume-down");
  assert.equal(s.volume, 1);
  s = act(s, "volume-up");
  assert.equal(s.volume, 2);
});

test("launcher device pages never execute service checks and return to desktop", () => {
  let s = act(act(preset("home"), "down"), "confirm");
  assert.equal(s.view, "menu"); assert.equal(s.owner, "launcher");
  s = act(s, "confirm"); assert.equal(s.view, "network"); assert.equal(s.stageAt, 0);
  s = act(act(s, "back"), "back"); assert.equal(s.view, "home");
  s = act(act(act(s, "up"), "up"), "confirm"); assert.equal(s.view, "external");
  assert.equal(act(s, "back").view, "home");
});

test("BLE session stays awake, expires without simulated success and exits to HOME",()=>{
  let s=act(act(preset("home"),"up"),"confirm",100);
  assert.equal(s.view,"ble");assert.equal(tick(s,40100).view,"ble");
  assert.equal(tick(s,300100).bleStatus,"expired");
  s=act(tick(s,300100),"confirm",300101);assert.equal(s.bleStatus,"ready");
  assert.equal(act(s,"back").view,"home");assert.equal(act(s,"ptt-down").view,"main");
});
