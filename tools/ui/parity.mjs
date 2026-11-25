import {
  mkdtemp,
  rm,
  access,
  readFile,
  writeFile,
  mkdir,
} from "node:fs/promises";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { execFileSync, spawnSync } from "node:child_process";
import assert from "node:assert/strict";
import { fileURLToPath } from "node:url";
import {
  PRESETS,
  preset,
  createState,
  title,
  textWindow,
  processingStage,
  processingWidth,
  pttBarHeight,
  footerAlpha,
  act,
  tick,
} from "../../tools/simulator/model.js";
import {
  T,
  MENU,
  menuSelection,
  PHASES,
  STATE_META,
  EVENTS,
  MS,
  L,
  phaseForEvent,
} from "../../tools/simulator/generated/ui-contract.js";
const root = fileURLToPath(new URL("../../", import.meta.url));
const bundled = join(root, ".tools/android/jdk/Contents/Home");
const javaHome =
  process.env.NEOTALKIE_JAVA_HOME ||
  process.env.JAVA_HOME ||
  (await access(join(bundled, "bin/javac")).then(
    () => bundled,
    () => null,
  ));
const bin = (name) => (javaHome ? join(javaHome, "bin", name) : name);
const dest = await mkdtemp(join(tmpdir(), "neotalkie-ui-parity-"));
const rows = [],
  expected = [],
  labels = [];
const push = (id, line, want) => {
  labels.push(id);
  rows.push(line);
  expected.push(want);
};
const enc = (s) => Buffer.from(s).toString("base64");
const measure = (s) =>
  Array.from(s).reduce((sum, c) => sum + (c.codePointAt(0) < 128 ? 10 : 20), 0);
const textCase = (id, s, width = L.CONTENT_WIDTH) => {
  const l = textWindow(s, (t) => measure(t)),
    processing = processingStage(s) > 0;
  // The production document has four slots; the panel chooses two rows for fixed prompts.
  const answer = STATE_META[s.phase].answer;
  push(
    id,
    [
      "text",
      enc(title(s)),
      enc(s.text),
      processing,
      answer,
      s.offset,
      width,
    ].join("\t"),
    [l.total, l.maxOffset, l.offset, l.expanded, ...l.lines.map(enc)].join(
      "\t",
    ),
  );
};
for (const p of PRESETS)
  if (!p.view || p.view === "main") textCase(p.id, preset(p.id));
for (const phase of Object.keys(PHASES))
  for (const text of [
    "",
    "一二三四五六七八九十甲乙丙",
    "Hello世界😀\n第二行\r段落",
    "W".repeat(80),
    "中".repeat(200),
  ])
    for (const offset of [0, 2, 2000])
      textCase(
        `text/${phase}/${text.length}/${offset}`,
        createState({ phase, text, offset }),
      );
for (let ms = -100; ms <= 30000; ms += 37)
  push("progress/" + ms, `progress\t${ms}`, String(processingWidth(ms)));
for (let value = 0; value <= 100; value++)
  push(
    "meter/" + value,
    `meter\t${value}`,
    String(pttBarHeight({ level: value })),
  );
for (const bytes of [0, 31968, 32000, 32032, 64000])
  push(
    "short/" + bytes,
    `short\t${bytes}`,
    String(bytes <= MS.SHORT_INPUT_MS * 32),
  );
for (const [event, phase] of Object.entries(EVENTS))
  push(
    "event/" + event,
    `event\t${event}`,
    phaseForEvent(event) + "\t" + enc(PHASES[phase]),
  );
for (const [phase, label] of Object.entries(PHASES))
  push(
    "stage/" + phase,
    `stage\t${enc(label)}`,
    String(processingStage(createState({ phase }))),
  );
for (const ms of [0, 1, 20, 50, 99, 100, 101])
  push(
    "fade/" + ms,
    `fade\t${ms}`,
    String(Math.ceil(255 * footerAlpha(1, 0, ms))),
  );
for (let selected = 0; selected < MENU.length; selected++)
  for (const direction of [-1, 1])
    push(
      `menu/${selected}/${direction}`,
      `menu\t${selected}\t${direction}`,
      String(menuSelection(selected, direction)),
    );
// Exercise the real simulator transition handlers, not a second test-only reducer.
let state = createState();
for (const [action, now, event] of [
  ["ptt-down", 0, "pttDown"],
  ["ptt-up", 2000, "pttUp"],
]) {
  state = act(state, action, now);
  push(
    "trace/" + action,
    `event\t${event}`,
    state.phase + "\t" + enc(title(state)),
  );
}
for (const [now, event] of [
  [2900, "recognitionDone"],
  [4000, "synthesis"],
  [4800, "reply"],
  [10300, "playbackDone"],
]) {
  state = tick(state, now);
  push(
    "trace/" + now,
    `event\t${event}`,
    state.phase + "\t" + enc(title(state)),
  );
}
state = tick(state, 10300 + MS.REPLY_HOLD_MS);
assert.equal(state.phase, "idle");
state = act(createState(), "ptt-down", 0);
state = act(state, "ptt-up", MS.SHORT_INPUT_MS);
assert.equal(state.phase, "tooShort");
assert.equal(tick(state, MS.SHORT_INPUT_MS + MS.SHORT_NOTICE_MS).phase, "idle");
try {
  const sources = ["UiContract", "TurnText", "RecordingFeedback"].map((n) =>
    join(root, "android/common/src/com/neotalkie/device", n + ".java"),
  );
  execFileSync(
    bin("javac"),
    [
      "-encoding",
      "UTF-8",
      "-d",
      dest,
      ...sources,
      join(root, "tools/ui/NativeUiProbe.java"),
    ],
    { stdio: "pipe" },
  );
  const run = spawnSync(
    bin("java"),
    ["-cp", dest, "com.neotalkie.device.NativeUiProbe"],
    {
      input: rows.join("\n") + "\n",
      encoding: "utf8",
      maxBuffer: 8 * 1024 * 1024,
    },
  );
  if (run.status !== 0)
    throw Error(run.stderr || run.error?.message || "Native probe failed");
  const actual = run.stdout.trimEnd().split("\n");
  assert.equal(actual.length, expected.length);
  for (let i = 0; i < expected.length; i++)
    assert.equal(actual[i], expected[i], labels[i]);
  console.log(`Java ↔ JavaScript UI parity: ${expected.length} cases passed`);
  if (process.env.UI_REPORT_DIR) {
    await mkdir(process.env.UI_REPORT_DIR, { recursive: true });
    await writeFile(
      join(process.env.UI_REPORT_DIR, "parity.json"),
      JSON.stringify({ count: expected.length, cases: labels }, null, 2),
    );
  }
} finally {
  await rm(dest, { recursive: true, force: true });
}
