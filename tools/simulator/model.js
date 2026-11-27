import {
  VIEWS,
  DEVICE,
  NETWORK,
  SUCCESS,
  SERVICES,
  pagesFor,
  networkType,
} from "./pages.js";
import {
  PHASES,
  ANDROID_BASELINE,
  MENU,
  DEVICE_MENU,
  menuSelection,
  SCENARIOS,
  MS,
  L,
  T,
  stage,
  phaseForEvent,
} from "./generated/ui-contract.js";
export { PHASES, MENU };
// Pure, offline interaction model. Times are simulation milliseconds, never wall clock dates.
export const WIDTH = L.WIDTH,
  HEIGHT = L.HEIGHT,
  CAPTURE_LIMIT = MS.CAPTURE_MS;
export const ANSWER =
  "安康鱼生活在海底，头上有像钓竿的发光器，利用光线吸引猎物，然后突然张开大嘴捕食。它的外形很特别，也叫灯笼鱼。";
export const PRESETS = SCENARIOS;
const number = (v, min, max, fallback) =>
  Number.isFinite(Number(v))
    ? Math.min(max, Math.max(min, Number(v)))
    : fallback;
function cleanFields(defaults, source) {
  return Object.fromEntries(
    Object.entries(defaults).map(([key, value]) => {
      const v = source?.[key];
      return [
        key,
        typeof value === "boolean"
          ? typeof v === "boolean"
            ? v
            : value
          : typeof value === "number"
            ? number(v, -100000, 1000000, value)
            : typeof v === "string"
              ? v.slice(0, 2000)
              : value,
      ];
    }),
  );
}
export function defaultHomeApps() {
  return [
    { label: T.text_005, desc: T.home_desc_talk, icon: "talk" },
    { label: T.device_settings, desc: T.home_desc_device, icon: "device" },
    { label: T.text_102, desc: T.home_desc_android, icon: "android" },
    { label: T.ble_entry, desc: T.home_desc_ble, icon: "ble" },
  ];
}
export function homeAppMeta(label) {
  const s = String(label ?? "");
  if (s === T.text_005) return { icon: "talk", desc: T.home_desc_talk };
  if (s === T.device_settings) return { icon: "device", desc: T.home_desc_device };
  if (s === T.text_102 || s === "系统设置") return { icon: "android", desc: T.home_desc_android };
  if (s === T.ble_entry) return { icon: "ble", desc: T.home_desc_ble };
  if (s.includes("音频互联") || s.includes("Audio Link")) return { icon: "audio", desc: T.home_desc_audio };
  return { icon: "app", desc: T.home_desc_app };
}
function cleanHomeApps(input) {
  if (Array.isArray(input.homeApps) && input.homeApps.length) {
    return input.homeApps.slice(0, 8).map((item) => {
      const label = String(item?.label ?? "").slice(0, 80);
      const fallback = homeAppMeta(label);
      return {
        label,
        desc: typeof item?.desc === "string" ? item.desc.slice(0, 80) : fallback.desc,
        icon: typeof item?.icon === "string" ? item.icon : fallback.icon,
      };
    });
  }
  if (Array.isArray(input.homeLabels) && input.homeLabels.length) {
    return input.homeLabels.slice(0, 8).map((v) => {
      const label = String(v).slice(0, 80);
      return { label, ...homeAppMeta(label) };
    });
  }
  return defaultHomeApps();
}
export function createState(input = {}) {
  const phase = Object.hasOwn(PHASES, input.phase) ? input.phase : "idle";
  const view = Object.hasOwn(VIEWS, input.view) ? input.view : "main";
  const state = {
    phase,
    view,
    hasContext:
      input.hasContext === true ||
      (input.hasContext == null && phase === "finished"),
    contextUntil: number(
      input.contextUntil,
      0,
      Number.MAX_SAFE_INTEGER,
      MS.CONTEXT_MS,
    ),
    title: typeof input.title === "string" ? input.title.slice(0, 40) : "",
    text:
      typeof input.text === "string"
        ? input.text.slice(0, 2000)
        : phase === "listening"
          ? T.release_send
          : T.hold_ptt,
    time: /^([01]\d|2[0-3]):[0-5]\d$/.test(input.time) ? input.time : "15:30",
    battery: number(input.battery, -1, 100, 78),
    charging: input.charging === true,
    wifi: number(input.wifi, -1, 4, input.id === "offline" ? -1 : 4),
    volume: Math.round(number(input.volume, 0, 5, 1)),
    level: number(input.level, 0, 100, 0),
    elapsed: number(input.elapsed, 0, CAPTURE_LIMIT, 0),
    transcript: String(input.transcript ?? "").slice(0, 2000),
    processingElapsed: number(input.processingElapsed, 0, 120000, 0),
    offset: Math.floor(number(input.offset, 0, 2000, 0)),
    selected: Math.floor(number(input.selected, 0, 7, 0)),
    page: Math.floor(number(input.page, 0, 16, 0)),
    device: cleanFields(DEVICE, input.device),
    network: {
      ...cleanFields(NETWORK, input.network),
      type: ["wifi", "offline", "mobile", "other"].includes(input.network?.type)
        ? input.network.type
        : "wifi",
    },
    checking: SERVICES.includes(input.checking) ? input.checking : SERVICES[0],
    results: (Array.isArray(input.results) ? input.results : SUCCESS)
      .slice(0, 3)
      .map((r) => cleanFields(SUCCESS[0], r)),
    checkQueue: Array.isArray(input.checkQueue)
      ? input.checkQueue.filter((v) => SERVICES.includes(v)).slice(0, 3)
      : [],
    checkIndex: Math.floor(number(input.checkIndex, 0, 2, 0)),
    owner: input.owner === "launcher" || input.view === "home" ? "launcher" : "talk",
    bleName: /^NeoTalkie-[A-F0-9]{4}$/.test(input.bleName) ? input.bleName : "NeoTalkie-A1B2",
    hasBinding: !!input.hasBinding,
    background: !!input.background,
    policyAction: [1,2].includes(input.policyAction) ? input.policyAction : 0,
    bleStatus: ["starting","ready","connected","passed","failed","unavailable","expired","pair","approved","connecting","success","auth","timeout","cancelled","wifiFailed"].includes(input.bleStatus) ? input.bleStatus : "ready",
    pairingCode: /^[A-F0-9]{6} [A-F0-9]{6}$/.test(input.pairingCode) ? input.pairingCode : "A1B2C3 D4E5F6",
    bleUntil: number(input.bleUntil,0,Number.MAX_SAFE_INTEGER,MS.BLE_SESSION_MS),
    otaStatus: ["checking","upToDate","available","updating","completed","error"].includes(input.otaStatus) ? input.otaStatus : "checking",
    otaProgress: Math.round(number(input.otaProgress, 0, 100, 0)),
    otaInstalling: input.otaInstalling === true,
    otaVersion: typeof input.otaVersion === "string" ? input.otaVersion.slice(0, 32) : "v0.2.4",
    otaCount: Math.round(number(input.otaCount, 1, 20, 3)),
    otaSize: typeof input.otaSize === "string" ? input.otaSize.slice(0, 24) : "2.4MB",
    otaStage: typeof input.otaStage === "string" ? input.otaStage.slice(0, 40) : "",
    otaDetail: typeof input.otaDetail === "string" ? input.otaDetail.slice(0, 40) : "",
    homeLabels:
      Array.isArray(input.homeLabels) && input.homeLabels.length
        ? input.homeLabels.slice(0, 8).map((v) => String(v).slice(0, 80))
        : [T.text_005, T.device_settings, T.text_102, T.ble_entry],
    homeApps: cleanHomeApps(input),
    footerOpacity:
      input.footerOpacity == null
        ? null
        : number(input.footerOpacity, 0, 1, null),
    volumeUntil: number(input.volumeUntil, 0, 60000, 0),
    resetAt: number(
      input.resetAt,
      0,
      600000,
      phase === "finished"
        ? MS.REPLY_HOLD_MS
        : phase === "tooShort"
          ? MS.SHORT_NOTICE_MS
          : 0,
    ),
    reviewUntil: number(input.reviewUntil, 0, 600000, 0),
    stageAt: number(input.stageAt, 0, 600000, 0),
    held: input.held === true,
    startedAt: number(input.startedAt, -120000, 600000, 0),
    previousView: Object.hasOwn(VIEWS, input.previousView)
      ? input.previousView
      : "main",
    idleSince: number(input.idleSince, 0, Number.MAX_SAFE_INTEGER, 0),
    keyCount: number(input.keyCount, 0, 1000000, 0),
  };
  if (typeof input.topic === "string" && /^\p{Script=Han}{1,6}$/u.test(input.topic)) state.topic = input.topic;
  if (Array.isArray(input.channelNames)) {
    const names = input.channelNames.filter(name => typeof name === "string" && name.trim())
      .slice(0, 3).map(name => name.trim().slice(0, 24));
    if (names.length) {
      state.channelNames = names;
      state.channelIndex = Math.floor(number(input.channelIndex, 0, names.length - 1, 0));
    }
  }
  if (input.channelChangedDuringTurn === true) state.channelChangedDuringTurn = true;
  if (state.view === "home" || state.view === "external")
    state.selected = Math.min(state.selected, state.homeLabels.length - 1);
  if (state.view === "menu")
    state.selected = Math.min(state.selected, (state.owner === "launcher" ? DEVICE_MENU : MENU).length - 1);
  if (view === "audioLink") {
    state.owner = "audio-link";
    state.linkDeviceName = String(input.linkDeviceName || "NeoTalkie-1234").slice(0, 80);
    state.linkPage = input.linkPage === "modes" ? "modes" : "home";
    state.linkFn = input.linkFn === true;
    state.linkFailed = input.linkFailed === true;
    state.linkSco = input.linkSco === true;
    state.linkStatus = String(input.linkStatus || "等待 PTT").slice(0, 80);
    state.linkToast = String(input.linkToast || "").slice(0, 80);
    state.linkMax = number(input.linkMax, 1, 100, 5);
    state.linkLevel = number(input.linkLevel ?? state.volume, 0, state.linkMax, 0);
    state.selected = Math.min(state.selected, 1);
  }
  const pages = pagesFor(state);
  if (pages.length) state.page = Math.min(state.page, pages.length - 1);
  return state;
}
export function preset(id) {
  return createState(PRESETS.find((p) => p.id === id) || {});
}
export function compact(s) {
  return s.view === "main" && !["idle", "unconfigured"].includes(s.phase);
}
export function title(s) {
  return (
    s.title ||
    (s.phase === "idle"
      ? s.hasContext
        ? s.topic || T.followup
        : T.phase_idle
      : PHASES[s.phase]) ||
    T.phase_idle
  );
}
export function ellipsis(text, measure, width = L.CONTENT_WIDTH) {
  if (measure(text) <= width) return text;
  const chars = Array.from(text);
  while (chars.length && measure(chars.join("") + "…") > width) chars.pop();
  return measure("…") <= width ? chars.join("") + "…" : "";
}
export function wrap(text, measure, width = L.CONTENT_WIDTH) {
  const lines = [];
  let line = "";
  for (const char of String(text ?? "").replace(/[\r\n]/g, " ")) {
    if (measure(line + char) > width && line) {
      lines.push(line);
      line = "";
    }
    if (measure(char) > width) {
      if (line) lines.push(line);
      lines.push(char);
      line = "";
    } else line += char;
  }
  if (line) lines.push(line);
  return lines;
}
export function processingStage(s) {
  if (s.view !== "main") return 0;
  return stage(s.phase);
}
export function textWindow(s, measure) {
  const processing = processingStage(s) > 0,
    rows = processing ? L.COMPACT_ROWS : L.EXPANDED_ROWS;
  const content = s.text;
  const body = wrap(processing ? "" : content, measure);
  const answer = ["speaking", "finished"].includes(s.phase) && body.length > 0;
  const all = processing
    ? [title(s), s.phase === "listening" ? T.release_send : T.please_wait]
    : answer
      ? body
      : [
          ellipsis(title(s), measure),
          ellipsis(String(content ?? "").replace(/[\r\n]/g, " "), measure),
        ];
  const maxOffset = Math.max(0, all.length - rows);
  const offset =
    processing && !s.reviewUntil
      ? maxOffset
      : Math.min(maxOffset, Math.max(0, s.offset));
  const lines = all.slice(offset, offset + rows);
  while (lines.length < L.EXPANDED_ROWS) lines.push("");
  return {
    lines,
    maxOffset,
    offset,
    total: all.length,
    rows,
    processing,
    expanded: !processing && compact(s) && all.length > 2,
  };
}
export function footerAlpha(from, target, elapsed) {
  return (
    from +
    ((target - from) * Math.max(0, Math.min(MS.FADE_MS, elapsed))) / MS.FADE_MS
  );
}
export function processingWidth(elapsedMs) {
  const elapsed = Math.max(0, elapsedMs);
  const remaining = 1 - elapsed / MS.WAIT_SWEEP_MS;
  const progress =
    elapsed <= MS.WAIT_SWEEP_MS
      ? 0.08 + 0.88 * (1 - remaining * remaining * remaining)
      : 0.96 +
        0.03 * (1 - Math.exp(-(elapsed - MS.WAIT_SWEEP_MS) / MS.WAIT_TAIL_MS));
  return Math.min(L.BAR_WIDTH - 1, Math.round(L.BAR_WIDTH * progress));
}
export function pttBarHeight(s) {
  return Math.ceil(
    (Math.max(0, Math.min(100, s.level)) * L.METER_HEIGHT) / 100,
  );
}
function ready(s) {
  return {
    ...s,
    phase: "idle",
    footerOpacity: null,
    title: "",
    text: T.hold_ptt,
    transcript: "",
    offset: 0,
    resetAt: 0,
    reviewUntil: 0,
    stageAt: 0,
    processingElapsed: 0,
    held: false,
    channelChangedDuringTurn: false,
  };
}
export function act(state, action, now = 0, maxOffset = 0) {
  let s = { ...state, keyCount: state.keyCount + 1, idleSince: now };
  if (s.hasContext && now >= s.contextUntil) s.hasContext = false;
  if (action === "power") {
    const previous = s.view === "sleep" ? s.previousView : s.view;
    return {
      ...(previous === "main" ? ready(s) : s),
      view: s.view === "sleep" ? previous : "sleep",
      previousView: previous,
      held: false,
      stageAt: 0,
    };
  }
  if (action === "ptt-down" && !["main","external"].includes(s.view) && !(s.view === "sleep" && s.previousView === "external"))
    s = {
      ...ready(s),
      view: "main",
      page: 0,
      volumeUntil: 0,
    };
  if (s.view === "sleep") return s;
  if (action === "home") return { ...ready(s), view: "home", owner: "launcher", selected: 0 };
  if (action === "back" && s.view === "home") return s;
  if (action === "back" && s.view === "ota" && s.otaStatus === "updating" && s.otaInstalling) return s;
  if (action === "back" && s.view === "ble" && s.policyAction) return {...s,policyAction:0};
  if (action === "back") {
    if (s.view === "main") {
      const homePrompt = s.phase === "idle" && !s.title && s.text === T.hold_ptt;
      const setupPrompt = s.phase === "unconfigured";
      const exit = s.volumeUntil <= now && (homePrompt || setupPrompt);
      return { ...(setupPrompt ? s : ready(s)), view: exit ? "home" : "main", volumeUntil: 0 };
    }
    return {
      ...ready(s),
      view:
        s.view === "menu" ? (s.owner === "launcher" ? "home" : "main") : ["external","ble"].includes(s.view) ? "home" : "menu",
      page: 0,
    };
  }
  if (
    s.view === "external" &&
    !["confirm", "ptt-up"].includes(action)
  )
    return s;
  if (action === "more") {
    if (s.view !== "main" || !(s.channelNames?.length > 1)) return s;
    const next = (s.channelIndex + 1) % s.channelNames.length;
    const inTurn = ["listening", "recognizing", "thinking", "searching", "synthesizing", "speaking", "recordCheck"].includes(s.phase);
    if (inTurn) s.channelChangedDuringTurn = true;
    else if (s.phase !== "unconfigured") s = ready(s);
    return { ...s, channelIndex: next, topic: "", hasContext: false, contextUntil: 0, volumeUntil: 0 };
  }
  if (action === "channel")
    return s.view === "main" && s.hasContext
      ? { ...ready(s), topic: "", hasContext: false, contextUntil: 0, volumeUntil: 0 }
      : s;
  if (action === "volume-up" || action === "volume-down")
    return {
      ...s,
      volume: Math.max(
        0,
        Math.min(5, s.volume + (action === "volume-up" ? 1 : -1)),
      ),
      volumeUntil: now + MS.VOLUME_MS,
    };
  if (action === "ptt-down") {
    if (s.view !== "main") return s;
    if (s.held) return s;
    if (networkType(s) === "offline")
      return {
        ...ready(s),
        view: "main",
        title: T.offline,
        text: T.connect_wifi,
      };
    return {
      ...ready(s),
      phase: phaseForEvent("pttDown"),
      text: T.release_send,
      held: true,
      startedAt: now,
      elapsed: 0,
      level: 45,
    };
  }
  if (action === "ptt-up" && s.view !== "main") return s;
  if (
    action === "ptt-up" &&
    s.phase === "listening" &&
    now - s.startedAt <= MS.SHORT_INPUT_MS
  )
    return {
      ...s,
      held: false,
      phase: phaseForEvent("shortInput"),
      text: "请重新说话",
      stageAt: 0,
      resetAt: now + MS.SHORT_NOTICE_MS,
    };
  if (action === "ptt-up")
    return s.phase === "listening"
      ? {
          ...s,
          held: false,
          phase: phaseForEvent("pttUp"),
          text: T.please_wait,
          stageAt: now + 900,
          processingStartedAt: now,
          processingElapsed: 0,
        }
      : { ...s, held: false };
  if (action === "up" || action === "down") {
    const d = action === "up" ? -1 : 1;
    if (s.view === "ble" && s.hasBinding)
      return {...s,policyAction:(s.policyAction+d+3)%3};
    if (s.view === "menu")
      return { ...s, selected: menuSelection(s.selected, d, s.owner === "launcher" ? DEVICE_MENU.length : MENU.length) };
    if (s.view === "home")
      return {
        ...s,
        selected: (s.selected + d + s.homeLabels.length) % s.homeLabels.length,
      };
    if (
      ["device", "network", "results", "checkError", "diagnostics"].includes(
        s.view,
      )
    )
      return {
        ...s,
        page: (s.page + d + pagesFor(s).length) % pagesFor(s).length,
      };
    if (s.view !== "main") return s;
    if (compact(s) && maxOffset > 0)
      return {
        ...s,
        offset: Math.min(maxOffset, Math.max(0, s.offset + d)),
        reviewUntil: now + MS.REVIEW_HOLD_MS,
        resetAt: 0,
        volumeUntil: 0,
      };
    return s;
  }
  if (action === "confirm") {
    if(s.view === "ble" && s.policyAction)
      return {...s,hasBinding:s.policyAction!==2,background:s.policyAction===1 && !s.background,
        policyAction:0,bleStatus:"ready",bleUntil:now+MS.BLE_SESSION_MS};
    if(s.view === "ble" && s.bleStatus === "pair")return {...s,bleStatus:"approved"};
    if (s.view === "ble" && ["expired","failed","unavailable"].includes(s.bleStatus))
      return {...s,bleStatus:"ready",bleUntil:now+MS.BLE_SESSION_MS};
    if (s.view === "main")
      return { ...ready(s), view: "menu", owner: "talk", selected: 0, volumeUntil: 0 };
    if (s.view === "menu") {
      const items = s.owner === "launcher" ? DEVICE_MENU : MENU;
      const picked = items[s.selected] || items[0];
      if (picked.id === "update")
        return { ...s, view: "ota", otaStatus: "checking", otaProgress: 0, otaInstalling: false };
      if (picked.id === "system")
        return { ...s, view: "device", page: 0 };
      if (s.owner === "launcher") return { ...s, view: "network", page: 0 };
      return {
        ...s,
        view: "diagnostics",
        page: 0,
        checking: SERVICES[0],
        checkQueue: [...SERVICES],
        checkIndex: 0,
        results: [],
        stageAt: now + 1800,
      };
    }
    if (s.view === "ota") {
      if (s.otaStatus === "available")
        return { ...s, otaStatus: "updating", otaProgress: 42, otaInstalling: false, otaStage: "下载 对讲 (1/3)", otaDetail: "1.0/" + s.otaSize };
      if (s.otaStatus === "upToDate" || s.otaStatus === "error")
        return { ...s, otaStatus: "checking", otaProgress: 0, otaInstalling: false };
      return s;
    }
    if (s.view === "checks") return s;
    if (s.view === "home")
      return { ...ready(s), view: ["main", "menu", "external", "ble"][s.selected] || "external", owner: s.selected === 0 ? "talk" : "launcher", selected: 0, bleStatus:"ready",bleUntil:now+MS.BLE_SESSION_MS };
  }
  return s;
}
export function tick(state, now) {
  let s = { ...state };
  if (s.hasContext && now >= s.contextUntil) s.hasContext = false;
  if (s.view === "sleep") return s;
  if(s.view === "ble" && s.bleStatus !== "expired" && now >= s.bleUntil) { s.bleStatus="expired";s.idleSince=now; }
  const busy = (s.view === "ble" && s.bleStatus !== "expired")
    || (s.view === "ota" && ["checking", "updating"].includes(s.otaStatus))
    || (s.view === "main" && ["listening", "recognizing", "thinking", "searching", "synthesizing", "speaking"].includes(s.phase))
    || (["checks", "diagnostics"].includes(s.view) && s.stageAt > 0);
  if (busy) s.idleSince = now;
  else if (s.view !== "external" && now - s.idleSince >= MS.IDLE_SCREEN_MS)
    return { ...s, previousView: s.view, view: "sleep", held: false };
  if (s.volumeUntil && now >= s.volumeUntil) s.volumeUntil = 0;
  if (s.view === "main" && s.phase === "listening") {
    s.elapsed = Math.min(CAPTURE_LIMIT, Math.max(0, now - s.startedAt));
    if (s.held)
      s.transcript = "安康鱼是什么，它生活在哪里".slice(
        0,
        Math.floor(s.elapsed / 250),
      );
    if (s.elapsed >= CAPTURE_LIMIT) {
      s.phase = phaseForEvent("pttUp");
      s.processingStartedAt = now;
      s.processingElapsed = 0;
      s.text = "30秒已发送";
      s.stageAt = now + 900;
    }
  }
  if (processingStage(s) > 1 && Number.isFinite(s.processingStartedAt))
    s.processingElapsed = Math.max(0, now - s.processingStartedAt);
  if (s.stageAt && now >= s.stageAt) {
    s.stageAt = 0;
    if (["checks", "diagnostics"].includes(s.view)) {
      s.results = [
        ...s.results,
        { ...SUCCESS.find((r) => r.name === s.checking) },
      ];
      s.checkIndex++;
      if (s.checkIndex < s.checkQueue.length) {
        s.checking = s.checkQueue[s.checkIndex];
        s.stageAt = now + 1800;
      } else {
        if (s.view !== "diagnostics") {
          s.view = "results";
          s.page = 0;
        }
      }
    } else if (s.phase === "recognizing") {
      s.phase = phaseForEvent("recognitionDone");
      s.text = T.please_wait;
      s.stageAt = now + 1100;
    } else if (s.phase === "thinking") {
      s.phase = phaseForEvent("synthesis");
      s.text = ANSWER.slice(0, 12);
      s.stageAt = now + 800;
    } else if (s.phase === "synthesizing") {
      s.phase = phaseForEvent("reply");
      s.offset = 0;
      s.reviewUntil = 0;
      s.text = ANSWER;
      s.stageAt = now + 5500;
    } else if (s.phase === "speaking") {
      s.phase = phaseForEvent("playbackDone");
      s.hasContext = !s.channelChangedDuringTurn;
      if (s.hasContext && s.text === ANSWER) s.topic = "安康鱼习性";
      s.contextUntil = now + MS.CONTEXT_MS;
      if (!s.reviewUntil) s.resetAt = now + MS.REPLY_HOLD_MS;
    }
  }
  if (s.reviewUntil && now >= s.reviewUntil) {
    s.reviewUntil = 0;
    s.offset = 0;
    if (["finished", "error", "nospeech"].includes(s.phase)) s = ready(s);
  }
  if (s.resetAt && now >= s.resetAt && !s.reviewUntil) s = ready(s);
  return s;
}
export function restoreSnapshot(input) {
  if (
    !input ||
    input.schema !== "neotalkie-radio-ui/v1" ||
    typeof input.state !== "object"
  )
    throw new Error("不是有效的手台界面快照");
  return createState(input.state);
}
export function snapshot(s) {
  return {
    schema: "neotalkie-radio-ui/v1",
    androidBaseline: ANDROID_BASELINE,
    state: { ...s },
  };
}
