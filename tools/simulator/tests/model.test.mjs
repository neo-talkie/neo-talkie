import test from "node:test";
import assert from "node:assert/strict";
import {
  createState,
  preset,
  PRESETS,
  act,
  tick,
  textWindow,
  footerAlpha,
  pttBarHeight,
  processingStage,
  processingWidth,
  title,
  restoreSnapshot,
  snapshot,
  CAPTURE_LIMIT,
} from "../model.js";
const measure = (s) => Array.from(s).length * 14;
test("fixed large text expands on the third row and the fade completes in 100 ms", () => {
  const measure=s=>Array.from(s).length*20;
  const s=createState({phase:"speaking",text:"一二三四五六七八九十甲乙"});
  assert.equal(textWindow(s,measure).expanded,false);
  s.text+="七";
  assert.equal(textWindow(s,measure).expanded,true);
  assert.equal(footerAlpha(1,0,0),1);
  assert.equal(footerAlpha(1,0,50),.5);
  assert.equal(footerAlpha(1,0,100),0);
  assert.equal(footerAlpha(0,1,100),1);
  assert.equal(footerAlpha(.5,1,50),.75);
});
test("every preset fits valid screen state and is serializable", () => {
  for (const p of PRESETS) {
    const s = preset(p.id);
    assert.equal(s.time, "15:30");
    assert.doesNotThrow(() => restoreSnapshot(snapshot(s)));
  }
});
test("reply body starts at top and four full rows fit without scrolling", () => {
  const s=createState({phase:"speaking",text:"一二三四五六七八九".repeat(4)});
  const view=textWindow(s,measure);
  assert.equal(view.lines.length,4);
  assert.equal(view.lines[0],"一二三四五六七八九");
  assert.equal(view.maxOffset,0);
  assert.ok(view.lines.every(l=>!l.includes("…")));
  s.offset=100;
  const last=textWindow(s,measure);
  assert.equal(last.offset,0);
  assert.equal(last.lines.join(""),s.text);
});
test("Unicode surrogate pairs remain intact at page boundaries", () => {
  const s = createState({ phase: "speaking", text: "😀".repeat(40) });
  for (let offset = 0; offset < 6; offset++) {
    s.offset = offset;
    for (const l of textWindow(s, measure).lines)
      assert.ok(!/[\uD800-\uDBFF](?![\uDC00-\uDFFF])/.test(l));
  }
});
test("scrolling replaces volume keys and clamps at both ends", () => {
  let s = preset("long-answer");
  const max = textWindow(s, measure).maxOffset;
  const original = s.volume;
  s = act(s, "down", 100, max);
  assert.equal(s.offset, 1);
  assert.equal(s.volume, original);
  s = act(s, "up", 200, max);
  s = act(s, "up", 300, max);
  assert.equal(s.offset, 0);
  for (let i = 0; i < 20; i++) s = act(s, "down", 400 + i, max);
  assert.equal(s.offset, max);
  assert.equal(s.volume, original);
});
test("five seconds since final navigation overrides post-playback three seconds", () => {
  let s = preset("finished");
  s = act(s, "down", 2000, 6);
  assert.equal(s.resetAt, 0);
  assert.equal(tick(s, 6000).phase, "finished");
  s = act(s, "up", 6000, 6);
  assert.equal(tick(s, 10999).phase, "finished");
  assert.equal(tick(s, 11000).phase, "idle");
});
test("completion while browsing preserves the existing review deadline", () => {
  let s = preset("long-answer");
  s.stageAt = 3000;
  s = act(s, "down", 2000, 6);
  s = tick(s, 3000);
  assert.equal(s.phase, "finished");
  assert.equal(s.resetAt, 0);
  assert.equal(tick(s, 6999).phase, "finished");
  assert.equal(tick(s, 7000).phase, "idle");
});
test("browsing timeout during speech returns to first window without interrupting speech", () => {
  let s = act(preset("long-answer"), "down", 0, 6);
  s = tick(s, 5000);
  assert.equal(s.phase, "speaking");
  assert.equal(s.offset, 0);
  assert.equal(s.reviewUntil, 0);
});
test("normal playback completion retains results for three seconds", () => {
  let s = preset("long-answer");
  s.stageAt = 100;
  s = tick(s, 100);
  assert.equal(tick(s, 3099).phase, "finished");
  assert.equal(tick(s, 3100).phase, "idle");
});
test("PTT interrupts playback, resets old timers, and reaches 30 second hard limit", () => {
  let s = act(preset("finished"), "down", 0, 6);
  s = act(s, "ptt-down", 200);
  assert.equal(s.reviewUntil, 0);
  assert.equal(s.phase, "listening");
  assert.equal(tick(s, 30199).phase, "listening");
  s = tick(s, 30200);
  assert.equal(s.phase, "recognizing");
  assert.equal(s.elapsed, CAPTURE_LIMIT);
});
test("volume keeps six original levels and overlay expires after 1.6 seconds", () => {
  let s = createState();
  for (let i = 0; i < 10; i++) s = act(s, "volume-up", 0);
  assert.equal(s.volume, 5);
  for (let i = 0; i < 10; i++) s = act(s, "volume-down", 0);
  assert.equal(s.volume, 0);
  assert.equal(tick(s, 1599).volumeUntil, 1600);
  assert.equal(tick(s, 1600).volumeUntil, 0);
  assert.equal(act(createState(), "up", 0).volume, 1);
});
test("weak signal and offline are distinct; offline PTT never starts recording", () => {
  assert.equal(preset("weak-wifi").wifi, 1);
  let s = preset("offline");
  assert.equal(s.wifi, -1);
  s = act(s, "ptt-down", 0);
  assert.equal(s.phase, "idle");
  assert.equal(s.title, "网络未连接");
});
test("PTT meter grows vertically, clamps levels, and stays still at constant input", () => {
  for (const [level, height] of [
    [0, 0],
    [1, 1],
    [20, 5],
    [50, 13],
    [100, 25],
    [-10, 0],
    [110, 25],
  ])
    assert.equal(pttBarHeight({ level }), height);
  const s = createState({ level: 40 });
  assert.equal(pttBarHeight(s), 10);
  assert.equal(pttBarHeight(tick(s, 1800)), 10);
});
test("menu network test stays entirely local",()=>{
 let s=act(createState(),'confirm');assert.equal(s.view,'menu');
 s=act(s,'confirm',100);assert.equal(s.view,'diagnostics');
 s=tick(s,1900);s=tick(s,3700);s=tick(s,5500);assert.equal(s.results.length,3);
});
test("snapshot rejects unknown format and constrains untrusted values", () => {
  assert.throws(() => restoreSnapshot({ schema: "bad" }));
  const s = restoreSnapshot({
    schema: "neotalkie-radio-ui/v1",
    state: {
      phase: "bad",
      view: "javascript:",
      volume: 999,
      battery: -1,
      text: "x".repeat(3000),
    },
  });
  assert.equal(s.phase, "idle");
  assert.equal(s.view, "main");
  assert.equal(s.volume, 5);
  assert.equal(s.battery, -1);
  assert.equal(s.text.length, 2000);
});

test("power cancels capture and wake never restarts the old recording", () => {
  let s = act(createState(), "ptt-down", 100);
  s = act(s, "power", 200);
  assert.equal(s.view, "sleep");
  s = tick(s, 60000);
  s = act(s, "power", 60001);
  assert.equal(s.view, "main");
  assert.equal(s.phase, "idle");
  assert.equal(s.stageAt, 0);
});

test("back stops an active turn and exits an idle screen to the local desktop", () => {
  let s = act(preset("long-answer"), "back");
  assert.equal(s.view, "main");
  assert.equal(s.phase, "idle");
  s = act(s, "back");
  assert.equal(s.view, "home");
});

test("pre-answer status stays visible; reply status never consumes a row", () => {
  for (const phase of ["speaking","finished"]) {
    assert.equal(textWindow(createState({phase,text:"你好"}),measure).lines[0],"你好");
  }
  assert.equal(textWindow(createState({phase:"synthesizing",text:""}),measure).lines[0],"合成中");
  assert.equal(textWindow(createState({phase:"thinking",text:"问题"}),measure).lines[0],"思考中");
});

test("system copy is bounded to two rows and cannot hide buttons",()=>{
  for(const phase of ["listening","recognizing","thinking","searching","error","nospeech"]){
    const layout=textWindow(createState({phase,text:"这是一段意外过长的系统提示"}),measure);
    assert.equal(layout.total,2);
    assert.equal(layout.expanded,false);
    assert.equal(layout.maxOffset,0);
  }
});

test("charging survives snapshot roundtrip and defaults off", () => {
  assert.equal(createState().charging,false);
  assert.equal(createState({charging:"false"}).charging,false);
  assert.equal(restoreSnapshot(snapshot(createState({charging:true}))).charging,true);
});

test("short releases skip recognition and return ready; longer speech still recognizes", () => {
  for(const duration of [0,999,1000,1001,2000]) {
    const s=act(act(createState(),"ptt-down",100),"ptt-up",100+duration);
    assert.equal(s.phase,duration<=1000?"tooShort":"recognizing");
    if(duration<=1000){assert.equal(s.stageAt,0);assert.equal(tick(s,100+duration+1499).phase,"tooShort");assert.equal(tick(s,100+duration+1500).phase,"idle");}
  }
});

test("ASR text remains hidden throughout input and processing",()=>{
  for(const [phase,label] of [["listening","正在听"],["recognizing","识别中"],["thinking","思考中"],["searching","联网查询"],["synthesizing","合成中"]]) {
    const s=createState({phase,transcript:"不应显示的多音字识别结果".repeat(10),text:"识别结果也不能通过详情泄露",offset:9,reviewUntil:5000});
    const l=textWindow(s,measure);
    assert.deepEqual(l.lines,[label,phase==="listening"?"松开发送":"请稍候","",""]);
    assert.equal(l.maxOffset,0);assert.equal(l.expanded,false);
    assert.equal(restoreSnapshot(snapshot(s)).transcript,s.transcript);
  }
});

test("waiting progress runs fast then slows without fake completion or stage reset",()=>{
  assert.equal(processingWidth(-1),processingWidth(0));
  assert.ok(processingWidth(500)>=74);assert.ok(processingWidth(2500)>=140);
  assert.ok(processingWidth(500)-processingWidth(0)>processingWidth(2500)-processingWidth(2000));
  let previous=0;for(let ms=0;ms<=120000;ms+=20){const w=processingWidth(ms);assert.ok(w>=previous&&w<148);previous=w;}
  let s=act(act(createState(),"ptt-down",0),"ptt-up",2000);
  s=tick(s,3000);assert.equal(s.processingElapsed,1000);assert.equal(s.phase,"thinking");
  s=tick(s,4100);assert.equal(s.processingElapsed,2100);assert.equal(s.phase,"synthesizing");
  const saved=restoreSnapshot(snapshot(s));assert.equal(saved.processingElapsed,2100);
});

test("context controls idle title, reset cancels pending work, and expiry removes the action",()=>{
  let s=createState();assert.equal(title(s),"新对话");
  assert.equal(act(s,"channel",0).hasContext,false);
  s=tick({...s,phase:"speaking",text:"回答",stageAt:10},10);
  assert.equal(s.hasContext,true);
  s=tick(s,3010);assert.equal(title(s),"继续追问");
  const expired=tick(s,300010);assert.equal(title(expired),"新对话");
  s=act({...s,phase:"thinking",stageAt:4000},"channel",3500);
  assert.equal(title(s),"新对话");assert.equal(s.stageAt,0);assert.equal(s.hasContext,false);
  assert.equal(tick(s,9999).phase,"idle");
  assert.equal(restoreSnapshot(snapshot(s)).hasContext,false);
});


test("idle display sleeps at 40 seconds; menu navigation resets the timer", () => {
  let s = createState();
  assert.equal(tick(s, 39999).view, "main");
  s = tick(s, 40000);
  assert.equal(s.view, "sleep");
  s = act(s, "ptt-down", 41000);
  assert.equal(s.view, "main");
  assert.equal(s.phase, "listening");
  assert.equal(s.held, true);
  s = act(createState(), "confirm", 20000);
  assert.equal(tick(s, 59999).view, "menu");
  assert.equal(tick(s, 60000).view, "sleep");
});
test("processing and playback keep the display on; completion starts a fresh idle window", () => {
  let s = createState({ phase: "speaking", stageAt: 50000 });
  s = tick(s, 45000);
  assert.equal(s.view, "main");
  s = tick(s, 50000);
  assert.equal(s.phase, "finished");
  assert.equal(tick(s, 89999).view, "main");
  assert.equal(tick(s, 90000).view, "sleep");
  s = tick(createState({view: "device"}), 40000);
  s = act(s, "ptt-down", 41000);
  assert.equal(s.phase, "listening");
  assert.equal(s.view, "main");
  const external = createState({ view: "sleep", previousView: "external" });
  assert.equal(act(external, "ptt-down", 41000).view, "sleep");
});
