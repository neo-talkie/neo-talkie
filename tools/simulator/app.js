import { ANDROID_BASELINE } from "./generated/ui-contract.js";
import { VIEWS } from "./pages.js";
import {
  PRESETS,
  PHASES,
  ANSWER,
  footerAlpha,
  createState,
  preset,
  title,
  act,
  tick,
  snapshot,
  restoreSnapshot,
} from "./model.js";
import {
  renderScreen,
  scaledScreen,
  deviceSVG,
  setScreenFont,
} from "./render.js";
const $ = (id) => document.getElementById(id),
  screen = $("native-screen");
let state = preset("ready"),
  selected = "ready",
  view = "device",
  now = 0,
  playing = false,
  lastTick = performance.now(),
  layout = {},
  autoReleaseAt = 0,
  toastTimer;
let saved = [];
try {
  const raw = JSON.parse(localStorage.getItem("neotalkie-radio-snapshots") || "[]");
  saved = Array.isArray(raw)
    ? raw.slice(0, 20).filter((x) => {
        try {
          restoreSnapshot(x.data);
          return true;
        } catch {
          return false;
        }
      })
    : [];
} catch {}
function toast(message) {
  $("toast").textContent = message;
  $("toast").classList.add("visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => $("toast").classList.remove("visible"), 2500);
}
function freeze() {
  playing = false;
  autoReleaseAt = 0;
}
function select(id) {
  freeze();
  selected = id;
  state = preset(id);
  now = 0;
  state.startedAt = -state.elapsed;
  state.processingStartedAt = -state.processingElapsed;
  render();
  renderPresets();
}
function renderPresets() {
  const nav = $("presets");
  nav.replaceChildren();
  const query = $("search").value.trim().toLowerCase();
  let group = "";
  PRESETS.filter((p) =>
    (p.id + p.group + p.name + p.note).toLowerCase().includes(query),
  ).forEach((p) => {
    if (p.group !== group) {
      group = p.group;
      const h = document.createElement("div");
      h.className = "preset-group";
      h.textContent = group;
      nav.append(h);
    }
    const b = document.createElement("button");
    b.className = "preset" + (p.id === selected ? " active" : "");
    b.setAttribute("aria-pressed", String(p.id === selected));
    const number = document.createElement("span");
    number.className = "number";
    number.textContent = String(PRESETS.indexOf(p) + 1).padStart(2, "0");
    const name = document.createElement("span");
    name.textContent = p.name;
    const dot = document.createElement("i");
    dot.className = "state-dot";
    b.append(number, name, dot);
    b.title = p.note;
    b.onclick = () => select(p.id);
    nav.append(b);
  });
  $("preset-count").textContent = PRESETS.length;
}
function refreshFields() {
  const vals = {
    "page-view": state.view,
    "page-index":state.page+1,
    selection:state.selected+1,
    "state-title":state.title,
    phase: state.phase,
    "body-text": state.text,
    transcript: state.transcript,
    "processing-time":state.processingElapsed/1000,
    clock: state.time,
    battery: state.battery,
    wifi: state.wifi,
    level: Math.round(state.level),
    elapsed: Math.floor(state.elapsed / 1000),
  };
  for (const [id, value] of Object.entries(vals))
    if (document.activeElement !== $(id)) $(id).value = value;
  $("text-count").textContent = `${Array.from(state.text).length} 字`;
  $("charging").checked = state.charging;
  $("has-context").checked = state.hasContext;
  $("battery-value").textContent = state.battery<0?"未知":`${state.battery}%`;
  $("level-value").textContent = `${Math.round(state.level)}%`;
  $("elapsed-value").textContent =
    `${Math.floor(state.elapsed / 1000)} / 30 秒`;
  $("volume").textContent = `${state.volume} / 5`;
  $("line-info").textContent = state.view!=="main" ? VIEWS[state.view] : (layout.expanded || layout.maxOffset>0)
    ? `20 px 大字 · 第 ${layout.offset+1}–${Math.min(layout.offset+layout.rows,layout.total)} 行 / 共 ${layout.total} 行`
    : "两行大字";
}
let fadeFrom=1, fadeTarget=1, fadeStarted=0, fadeTimer;
function render() {
  clearTimeout(fadeTimer);
  layout = renderScreen(screen, state, now);
  const wall=performance.now(), target=layout.expanded ? 0 : 1;
  if (target!==fadeTarget) {
    fadeFrom=footerAlpha(fadeFrom,fadeTarget,wall-fadeStarted);
    fadeTarget=target;fadeStarted=wall;
  }
  const alpha=footerAlpha(fadeFrom,fadeTarget,wall-fadeStarted);
  if (alpha!==target) {
    layout=renderScreen(screen,state,now,alpha);
    fadeTimer=setTimeout(render,20);
  }
  state.offset = layout.offset || 0;
  const scaled = scaledScreen(
    screen,
    Number($("zoom").value),
    $("pixel-grid").checked,
  );
  const large = $("large-screen");
  large.width = scaled.width;
  large.height = scaled.height;
  large.getContext("2d").drawImage(scaled, 0, 0);
  const data = screen.toDataURL();
  if (!$("device-art").firstChild)
    $("device-art").innerHTML = deviceSVG(data, state.volume);
  else {
    $("device-art").querySelector("image").setAttribute("href", data);
    const indicator = $("device-art").querySelector("[transform]");
    indicator?.setAttribute(
      "transform",
      `rotate(${(state.volume - 2.5) * 12} 331 106)`,
    );
  }
  const scene = PRESETS.find((p) => p.id === selected);
  $("scene-title").textContent = scene?.name || "自定义状态";
  $("scene-note").textContent = scene?.note || "调节参数，预览当前界面";
  $("play").textContent = playing ? "Ⅱ" : "▶";
  $("play").setAttribute("aria-label", playing ? "暂停演示" : "开始演示");
  $("play-label").textContent = playing ? "演示进行中" : "画面已冻结";
  $("play-note").textContent =
    state.reviewUntil > now
      ? `翻看中 · ${Math.ceil((state.reviewUntil - now) / 1000)} 秒后恢复`
      : playing
        ? "模拟对话流程 · 不发送任何语音"
        : "自由选状态，或播放一轮完整对话";
  $("sim-time").textContent =
    `${String(Math.floor(now / 60000)).padStart(2, "0")}:${((now / 1000) % 60).toFixed(1).padStart(4, "0")}`;
  $("timeline-fill").style.width = `${Math.min(100, now / 300)}%`;
  document
    .querySelectorAll("[data-key=ptt]")
    .forEach((b) => b.classList.toggle("held", state.held));
  refreshFields();
}
function changeView(next) {
  view = next;
  for (const name of ["device", "screen", "board"])
    $(name + "-view").hidden = name !== view;
  document
    .querySelectorAll("[data-view]")
    .forEach((b) =>
      b.setAttribute("aria-pressed", String(b.dataset.view === view)),
    );
  $("zoom").disabled = view !== "screen";
  $("pixel-grid").disabled = view !== "screen";
  if (view === "board") renderBoard();
  render();
}
function action(key) {
  if (key === "system-menu") key = "confirm";
  if (key === "ptt-down" || key === "ptt-up") {
    playing = true;
    lastTick = performance.now();
    autoReleaseAt = 0;
  }
  if (["up", "down", "volume-up", "volume-down", "confirm", "more", "back"].includes(key)) {
    playing = true;
    lastTick = performance.now();
  }
  state.footerOpacity=null;
  state = act(state, key, now, layout.maxOffset || 0);
  render();
}
function bindKeys() {
  document.querySelectorAll("[data-key]").forEach((b) => {
    if (b.dataset.key === "ptt") {
      let pointerPtt = false;
      b.addEventListener("pointerdown", (e) => {
        e.preventDefault();
        b.setPointerCapture(e.pointerId);
        pointerPtt = true;
        if (pointerPtt) action("ptt-down");
      });
      const release = () => {
        if(pointerPtt || state.held || state.view==='home')action("ptt-up");
        pointerPtt=false;
      };
      b.addEventListener("pointerup", () => {
        if (pointerPtt) release();
        else action("confirm");
      });
      b.addEventListener("pointercancel", release);
      b.addEventListener("lostpointercapture", release);
      b.addEventListener("keydown", (e) => {
        if (e.code === "Enter" && !e.repeat && b.dataset.key === "ptt") {
          e.preventDefault();
          action("ptt-down");
        }
      });
      b.addEventListener("keyup", (e) => {
        if (e.code === "Enter") {
          e.preventDefault();
          release();
        }
      });
    } else b.addEventListener("click", () => action(b.dataset.key));
  });
  const map = {
    ArrowLeft: "channel",
    ArrowRight: "more",
    ArrowUp: "up",
    ArrowDown: "down",
    Enter: "confirm",
    Escape: "back",
    KeyM: "more",
    KeyP: "power",
    KeyH: "home",
    Equal: "volume-up",
    NumpadAdd: "volume-up",
    Minus: "volume-down",
    NumpadSubtract: "volume-down",
  };
  document.addEventListener("keydown", (e) => {
    if (e.target.closest?.("input,textarea,select,summary")) return;
    if (e.code === "Space") {
      e.preventDefault();
      if (!e.repeat) action("ptt-down");
    } else if (map[e.code]) {
      e.preventDefault();
      if (!e.repeat || ["ArrowUp", "ArrowDown"].includes(e.code))
        action(map[e.code]);
    }
  });
  document.addEventListener("keyup", (e) => {
    if (e.code === "Space" && (state.held || state.view === "home")) {
      e.preventDefault();
      action("ptt-up");
    }
  });
  window.addEventListener("blur", () => {
    if (state.held) action("ptt-up");
    freeze();
    render();
  });
  $("device-wrap").addEventListener(
    "wheel",
    (e) => {
      if (e.target.closest(".knob-left,.knob-right")) {
        e.preventDefault();
        action(e.deltaY < 0 ? "volume-up" : "volume-down");
      }
    },
    { passive: false },
  );
}
function renderBoard() {
  $("board").replaceChildren();
  for (const p of PRESETS) {
    const b = document.createElement("button");
    b.className = "board-card";
    const c = document.createElement("canvas");
    const s = preset(p.id);
    renderScreen(c, s, 0);
    const label = document.createElement("span");
    label.textContent = `${String(PRESETS.indexOf(p)+1).padStart(3,"0")} · ${p.name}`;
    const note = document.createElement("small");
    note.textContent = p.note;
    b.append(c, label, note);
    b.onclick = () => {
      select(p.id);
      changeView("screen");
    };
    $("board").append(b);
  }
}
function safeFilename(value) {
  return value.replace(/[^\p{L}\p{N}_-]/gu, "-").slice(0, 40) || "screen";
}
function download(blob, name) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = name;
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}
async function png(canvas) {
  return new Promise((resolve, reject) =>
    canvas.toBlob(
      (b) => (b ? resolve(b) : reject(new Error("图片导出失败"))),
      "image/png",
    ),
  );
}
async function deviceCanvas() {
  const svg = deviceSVG(screen.toDataURL(), state.volume);
  const img = new Image();
  img.src = "data:image/svg+xml;charset=utf-8," + encodeURIComponent(svg);
  await img.decode();
  const c = document.createElement("canvas");
  c.width = 840;
  c.height = 1360;
  const x = c.getContext("2d");
  x.fillStyle = "#f2f4ef";
  x.fillRect(0, 0, c.width, c.height);
  x.imageSmoothingEnabled = false;
  x.drawImage(img, 0, 0, c.width, c.height);
  return c;
}
function manifest() {
  return {schema:'neotalkie-radio-regression/v1',androidBaseline:ANDROID_BASELINE,screen:{width:160,height:128},
    scope:'NeoTalkie app-owned UI templates and named boundary cases; synthetic data; ROM screens and real hardware timing excluded',
    cases:PRESETS.map((p,i)=>({number:i+1,id:p.id,name:p.name,group:p.group,note:p.note,...snapshot(preset(p.id))}))};
}
async function boardCanvas() {
  await document.fonts.ready;
  const cols=5, cellW=352, cellH=326;
  const groups=[...new Set(PRESETS.map(p=>p.group))];
  const totalHeight=140+groups.reduce((h,g)=>h+54+Math.ceil(PRESETS.filter(p=>p.group===g).length/cols)*cellH,0);
  const c=document.createElement('canvas');c.width=cols*cellW+48;c.height=totalHeight;
  const x=c.getContext('2d');x.fillStyle='#eff3ef';x.fillRect(0,0,c.width,c.height);x.imageSmoothingEnabled=false;
  const label=(value,px,py,size=16,color='#274b3d')=>{x.fillStyle=color;x.font=`${size}px "Radio CJK",sans-serif`;x.fillText(value,px,py);};
  label('NEOTALKIE / 手台全量界面回归',32,46,30);
  label(`Android ${ANDROID_BASELINE} · 160 × 128 px · ${PRESETS.length} 个命名场景 · 2× 整数放大`,32,78,18);
  label('离线模拟 / 参数可编辑 / 系统应用图标为占位 / 字体栅格化与真机可能有细微差异',32,106,15,'#65786f');
  let top=140;
  for(const group of groups){
    const items=PRESETS.filter(p=>p.group===group);
    label(`${group}  /  ${items.length}`,32,top+30,23);top+=54;
    for(const [index,p] of items.entries()){
      const small=document.createElement('canvas');renderScreen(small,preset(p.id),0);
      const px=24+(index%cols)*cellW,py=top+Math.floor(index/cols)*cellH;
      x.fillStyle='#fff';x.fillRect(px,py,cellW-12,cellH-12);
      x.drawImage(small,px+10,py+10,320,256);
      const number=String(PRESETS.indexOf(p)+1).padStart(3,'0');
      label(`${number}  ${p.name}`,px+10,py+287,14);
      label(p.id,px+10,py+306,11,'#647b70');
    }
    top+=Math.ceil(items.length/cols)*cellH;
  }
  return c;
}
async function exportImage(kind = $("export-kind").value) {
  try {
    const c =
      kind === "device"
        ? await deviceCanvas()
        : kind === "board"
          ? await boardCanvas()
          : scaledScreen(screen, kind === "screen1" ? 1 : 4, false);
    download(await png(c), `neotalkie-${safeFilename(selected)}-${kind}.png`);
    toast("图片已导出");
  } catch (e) {
    toast("导出失败：" + e.message);
  }
}
function renderSaved() {
  const list = $("saved");
  list.replaceChildren();
  $("saved-count").textContent = saved.length;
  if (!saved.length) {
    const e = document.createElement("p");
    e.className = "saved-empty";
    e.textContent = "把想留住的画面存下来，\n下一次打开仍可继续查看。";
    list.append(e);
  }
  saved.forEach((entry, index) => {
    const row = document.createElement("div");
    row.className = "saved-item";
    const b = document.createElement("button");
    b.textContent = String(entry.name).slice(0, 60);
    b.onclick = () => {
      freeze();
      now = 0;
      state = restoreSnapshot(entry.data);
      state.startedAt = -state.elapsed;
      state.processingStartedAt = -state.processingElapsed;
      selected = "custom";
      render();
      renderPresets();
    };
    const remove = document.createElement("button");
    remove.textContent = "×";
    remove.setAttribute("aria-label", `删除快照 ${index + 1}`);
    remove.onclick = () => {
      saved.splice(index, 1);
      persist();
      renderSaved();
    };
    row.append(b, remove);
    list.append(row);
  });
}
function persist() {
  try {
    localStorage.setItem("neotalkie-radio-snapshots", JSON.stringify(saved));
  } catch {
    toast("浏览器存储不可用，请导出参数文件留档");
  }
}
$("save-shot").onclick = () => {
  const entry = {
    name: `${PRESETS.find((p) => p.id === selected)?.name || "自定义"} · ${String(saved.length + 1).padStart(2, "0")}`,
    data: snapshot(state),
  };
  saved.unshift(entry);
  saved = saved.slice(0, 20);
  persist();
  renderSaved();
  toast("已保存到本机快照");
};
$("export").onclick = () => exportImage();
$("export-board").onclick = () => exportImage("board");
$("save-json").onclick = () =>
  download(
    new Blob([JSON.stringify(snapshot(state), null, 2)], {
      type: "application/json",
    }),
    `neotalkie-${safeFilename(selected)}.json`,
  );
$("load-json").onclick = () => $("json-file").click();
$("json-file").onchange = async (e) => {
  try {
    const file = e.target.files[0];
    if (!file) return;
    if (file.size > 100000) throw new Error("参数文件过大");
    state = restoreSnapshot(JSON.parse(await file.text()));
    freeze();
    now = 0;
    state.startedAt = -state.elapsed;
    state.processingStartedAt = -state.processingElapsed;
    selected = "custom";
    render();
    renderPresets();
    toast("参数已载入");
  } catch (error) {
    toast(error.message);
  } finally {
    e.target.value = "";
  }
};
$("font-file").onchange = async (e) => {
  try {
    const file = e.target.files[0];
    if (!file) return;
    if (file.size > 20 * 1024 * 1024) throw new Error("字体请小于 20 MB");
    const name = "DeviceFont" + Date.now();
    const face = new FontFace(name, await file.arrayBuffer());
    await face.load();
    document.fonts.add(face);
    setScreenFont(name);
    $("font-status").textContent = "设备字体：" + file.name;
    render();
    if (view === "board") renderBoard();
    toast("设备字体已载入，仅本次会话生效");
  } catch (error) {
    toast("字体无法载入：" + error.message);
  }
};
$("baseline-version").textContent=ANDROID_BASELINE;
$("page-view").replaceChildren(...Object.entries(VIEWS).map(([v,label])=>new Option(label,v)));
for(const [id,key] of [['page-view','view'],['page-index','page'],['selection','selected'],['state-title','title']]) {
  $(id).onchange=()=>{
    freeze();
    const value=['page','selected'].includes(key)?Number($(id).value)-1:$(id).value;
    state=createState({...state,[key]:value,...(key==='view'?{page:0,selected:0}: {})});
    selected='custom';render();renderPresets();
  };
}
$('read-state').onclick=()=>{$('state-json').value=JSON.stringify(state,null,2);$('state-error').textContent='';};
$('apply-state').onclick=()=>{
  try {
    if($('state-json').value.length>100000)throw new Error('参数内容过大');
    const input=JSON.parse($('state-json').value);
    if(!input||Array.isArray(input)||typeof input!=='object')throw new Error('请使用状态对象');
    state=createState(input);freeze();now=0;state.startedAt=-state.elapsed;state.processingStartedAt=-state.processingElapsed;
    selected='custom';render();renderPresets();$('state-error').textContent='已应用（画面冻结）';
  }catch(e){$('state-error').textContent='未应用：'+e.message;}
};
$('export-manifest').onclick=()=>download(new Blob([JSON.stringify(manifest(),null,2)],{type:'application/json'}),`neotalkie-ui-regression-${ANDROID_BASELINE}.json`);

$("search").oninput = renderPresets;
document
  .querySelectorAll("[data-view]")
  .forEach((b) => (b.onclick = () => changeView(b.dataset.view)));
$("zoom").onchange = render;
$("pixel-grid").onchange = render;
for (const [id, key] of [
  ["battery", "battery"],
  ["wifi", "wifi"],
  ["clock", "time"],
  ["level", "level"],
  ["elapsed", "elapsed"],
])
  $(id).addEventListener("input", () => {
    const value = id === "clock" ? $(id).value : Number($(id).value);
    if (id === "clock" && !value) return;
    state[key] = id === "elapsed" ? value * 1000 : value;
    if (id === "elapsed") {
      state.startedAt = now - state.elapsed;
      state.processingStartedAt = now - state.processingElapsed;
      freeze();
    }
    render();
  });
$("has-context").onchange = () => {
  state.hasContext=$("has-context").checked;
  state.contextUntil=now+300000;
  render();
};
$("charging").onchange = () => {
  state.charging = $("charging").checked;
  render();
};
$("phase").replaceChildren(
  ...Object.entries(PHASES).map(([value, label]) => new Option(label, value)),
);
$("phase").onchange = () => {
  freeze();
  state = createState({
    ...state,
    phase: $("phase").value,
    view: "main",
    title: "",
  });
  state.startedAt = now - state.elapsed;
  state.processingStartedAt = now - state.processingElapsed;
  state.resetAt = state.phase === "finished" ? now + 3000 : 0;
  selected = "custom";
  render();
  renderPresets();
};
$("processing-time").oninput = () => {
  freeze();state.processingElapsed=Number($("processing-time").value)*1000;
  state.processingStartedAt=now-state.processingElapsed;render();
};
$("transcript").oninput = () => {
  freeze();
  state.transcript=$("transcript").value.slice(0,2000);
  state.offset=0; state.reviewUntil=0;
  selected="custom";
  render(); renderPresets();
};
$("body-text").oninput = () => {
  freeze();
  state.text = $("body-text").value.slice(0, 2000);
  state.offset = 0;
  state.reviewUntil = 0;
  if (state.view !== "main" || state.phase === "idle") {
    state.view = "main";
    state.phase = "speaking";
    state.title = "";
  }
  selected = "custom";
  render();
  renderPresets();
};
$("sample-text").onclick = () => {
  state = {
    ...state,
    phase: "speaking",
    view: "main",
    title: "",
    text: ANSWER,
    offset: 0,
  };
  selected = "custom";
  freeze();
  render();
  renderPresets();
};
$("play").onclick = () => {
  playing = !playing;
  lastTick = performance.now();
  if (playing) {
    state.processingStartedAt = now - state.processingElapsed;
    if (state.view === "main" && state.phase === "idle") {
      state = act(state, "ptt-down", now);
      autoReleaseAt = now + 3200;
    } else if (state.phase === "listening")
      state.startedAt = now - state.elapsed;
    else if (
      ["recognizing", "thinking", "synthesizing", "speaking"].includes(
        state.phase,
      ) &&
      !state.stageAt
    )
      state.stageAt = now + (state.phase === "speaking" ? 5500 : 1000);
    else if (state.view === "checks") state.stageAt = now + 1800;
  }
  render();
};
$("reset").onclick = () => select(selected === "custom" ? "ready" : selected);
try {
  await Promise.all([
    document.fonts.load('14px "Radio CJK"'),
    document.fonts.load('20px "Radio Latin"'),
  ]);
} catch {
  toast("内置字体加载失败，暂用系统字体；请检查本地字体资源");
}
bindKeys();
renderPresets();
renderSaved();
changeView("device");
$("read-state").click();
setInterval(() => {
  const wall = performance.now(),
    delta = wall - lastTick;
  lastTick = wall;
  if (!playing) return;
  now += Math.min(delta, 1000);
  if (autoReleaseAt && now >= autoReleaseAt) {
    state = act(state, "ptt-up", now);
    autoReleaseAt = 0;
  }
  const previous = state.phase;
  state = tick(state, now);
  if (previous !== "idle" && state.phase === "idle") playing = false;
  render();
}, 100);
// Read-only inspection + pure rendering hooks used by local, offline browser checks.
window.radioSimulator = {
  get state() {
    return structuredClone(state);
  },
  get layout() {
    return structuredClone(layout);
  },
  get now() {
    return now;
  },
  select,
  action,
  renderScreen,
  scaledScreen,
  deviceCanvas,
  boardCanvas,
  manifest,
  snapshot: () => snapshot(state),
};
