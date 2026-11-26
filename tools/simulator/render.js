import { T, L, C, MS, GRAPHICS, MENU, DEVICE_MENU } from "./generated/ui-contract.js";
import { pagesFor, networkType } from "./pages.js";
import {
  WIDTH,
  HEIGHT,
  title,
  textWindow,
  ellipsis,
  pttBarHeight,
  processingStage,
  processingWidth,
} from "./model.js";
export const COLORS = {
  bg: C.BG,
  white: C.WHITE,
  ink: C.INK,
  teal: C.PTT,
  orange: "#f04e1a",
  orangeText: "#ff6b3d",
  dim: C.DIM,
};
const DIGITS = GRAPHICS.digits;
let screenFont = "Radio CJK";
export function setScreenFont(name) {
  screenFont = name;
}
export function font(ctx, size) {
  ctx.font = `${size}px "Radio Latin", "${screenFont}", sans-serif`;
  ctx.textBaseline = "alphabetic";
}
export function measureFor(ctx, size = 14) {
  font(ctx, size);
  return (t) => ctx.measureText(t).width;
}
function rect(c, x, y, w, h, color) {
  c.fillStyle = color;
  c.fillRect(x, y, w, h);
}
function text(c, s, x, y, size = 14, color = COLORS.ink, width = Infinity) {
  font(c, size);
  c.fillStyle = color;
  c.fillText(
    width === Infinity ? s : ellipsis(s, (t) => c.measureText(t).width, width),
    x,
    y,
  );
}
function round(c, x, y, w, h, r, color, stroke = 0) {
  c.beginPath();
  c.roundRect(x, y, w, h, r);
  c.fillStyle = color;
  c.strokeStyle = color;
  c.lineWidth = stroke;
  if (stroke) c.stroke();
  else c.fill();
}
export function numbers(c, s, x, y, scale = 2, color = COLORS.white) {
  let cursor = x;
  for (const ch of s) {
    const rows =
      DIGITS[Number(ch)] ?? (ch === ":" ? [0, 1, 0, 1, 0] : [1, 1, 2, 4, 4]);
    const cols = ch === ":" ? 1 : 3;
    for (let row = 0; row < 5; row++)
      for (let col = 0; col < cols; col++)
        if (rows[row] & (1 << (cols - col - 1)))
          rect(c, cursor + col * scale, y + row * scale, scale, scale, color);
    cursor += (cols + 1) * scale;
  }
  return cursor - x - scale;
}
function wifi(c, level) {
  const shapes = [
    [
      [6, 0, 7, 2],
      [3, 2, 3, 2],
      [13, 2, 3, 2],
      [0, 4, 3, 2],
      [16, 4, 3, 2],
    ],
    [
      [7, 5, 5, 2],
      [4, 7, 3, 2],
      [12, 7, 3, 2],
      [3, 9, 2, 1],
      [14, 9, 2, 1],
    ],
    [
      [8, 10, 3, 2],
      [6, 12, 2, 1],
      [11, 12, 2, 1],
    ],
    [[8, 14, 3, 3]],
  ];
  shapes.forEach((sh, i) =>
    sh.forEach(([x, y, w, h]) =>
      rect(c, 103 + x, 4 + y, w, h, level >= 4 - i ? COLORS.white : COLORS.dim),
    ),
  );
}
function header(c, s) {
  numbers(c, s.time, 6, 3, 4);
  rect(c, 87, 3, 4, 2, COLORS.white);
  rect(c, 84, 5, 10, 17, COLORS.white);
  rect(c, 86, 7, 6, 13, COLORS.bg);
  if (s.battery > 0) {
    const fill = Math.max(1, Math.floor((s.battery * 13) / 100));
    rect(c, 86, 20 - fill, 6, fill, COLORS.white);
  }
  if (s.charging) {
    GRAPHICS.bolt.forEach((bits, y) => {
      for (let x = 0; x < 6; x++)
        if (bits & (1 << (5 - x))) rect(c, 96 + x, 7 + y, 1, 1, COLORS.white);
    });
  }
  const type = networkType(s);
  if (type === "wifi") wifi(c, s.wifi);
  else
    text(
      c,
      type === "offline"
        ? T.text_095
        : type === "mobile"
          ? T.text_096
          : T.text_110,
      103,
      19,
      14,
      COLORS.white,
    );
  rect(c, 6, 26, 148, 1, COLORS.white);
}
// Android's small icons use a non-antialiased Paint. Rasterize once then threshold alpha.
function icon(draw, w, h) {
  const canvas = document.createElement("canvas");
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext("2d");
  draw(ctx);
  const pixels = ctx.getImageData(0, 0, w, h);
  for (let i = 3; i < pixels.data.length; i += 4)
    pixels.data[i] = pixels.data[i] >= 128 ? 255 : 0;
  ctx.putImageData(pixels, 0, 0);
  return canvas;
}
let icons;
function getIcons() {
  return (icons ??= {
    ptt: icon(
      (c) => {
        round(c, 1, 1, 14, 43, 7, COLORS.teal, 2);
        rect(c, 6, 10, 4, 25, COLORS.teal);
      },
      16,
      45,
    ),
    frame: icon(
      (c) => {
        round(c, 1, 1, 14, 43, 7, COLORS.teal, 2);
      },
      16,
      45,
    ),
    circle: icon(
      (c) => {
        c.strokeStyle = "#fff";
        c.lineWidth = 2;
        for (const radius of [7, 3]) {
          c.beginPath();
          c.arc(8, 8, radius, 0, Math.PI * 2);
          c.stroke();
        }
      },
      16,
      16,
    ),
    line: icon(
      (c) => {
        c.strokeStyle = "#fff";
        c.lineWidth = 2;
        c.beginPath();
        c.arc(8, 8, 7, 0, Math.PI * 2);
        c.stroke();
        rect(c, 7, 4, 2, 8, "#fff");
      },
      16,
      16,
    ),
  });
}
function volume(c, s, level = s.volume, maximum = 5) {
  const segments = Math.min(maximum, 15);
  const step = 114 / segments;
  rect(c, 0, L.FOOTER_TOP, L.WIDTH, L.HEIGHT - L.FOOTER_TOP, C.WAIT_BG);
  rect(c, 0, L.FOOTER_TOP, L.WIDTH, 1, C.WAIT_EDGE);
  text(c, T.volume, L.LEFT, L.FOOTER_LABEL_Y, L.FOOTER_FONT, COLORS.white);
  for (let i = 0; i < segments; i++)
    rect(
      c,
      Math.round(6 + i * step),
      111,
      Math.round(6 + (i + 1) * step) - 3 - Math.round(6 + i * step),
      12,
      (i + 1) * maximum <= level * segments ? C.WAIT_BAR : C.WAIT_EDGE,
    );
  const value = `${level}/${maximum}`;
  numbers(c, value, 132 - (value.length - 3) * 8, 112, 2, COLORS.white);
}
function audioLink(c, s) {
  rect(c, 0, 0, WIDTH, HEIGHT, "#101010");
  const modes = s.linkPage === "modes";
  const labels = ["普通 PTT", "Fn 首尾点按"];
  const rows = modes
    ? labels.map((label, i) => (i === Number(s.linkFn) ? "✓ " : "") + label)
    : [s.linkFailed ? "重新启用" : "蓝牙配对", "模式 · " + labels[Number(s.linkFn)]];
  c.textAlign = "center";
  text(c, modes ? "讲话模式" : s.linkDeviceName, 80, 17, 15, "#fff", 156);
  text(c, s.linkStatus, 80, 38, 14, "#ccc", 156);
  rows.forEach((row, i) => {
    rect(c, 0, 44 + i * 41, WIDTH, 39, i === s.selected ? COLORS.orange : "#2a2a2a");
    text(c, row, 80, 69 + i * 41, 15, "#fff", 156);
  });
  if (s.linkToast) {
    rect(c, 4, 79, 152, 31, "#454545");
    text(c, s.linkToast, 80, 99, 12, "#fff", 147);
  }
  c.textAlign = "left";
}
export function renderScreen(canvas, s, now = 0, footerOpacity) {
  if (canvas.width !== WIDTH) canvas.width = WIDTH;
  if (canvas.height !== HEIGHT) canvas.height = HEIGHT;
  const c = canvas.getContext("2d");
  c.imageSmoothingEnabled = false;
  c.globalAlpha = 1;
  c.clearRect(0, 0, WIDTH, HEIGHT);
  rect(
    c,
    0,
    0,
    WIDTH,
    HEIGHT,
    s.view === "sleep" ? "#030605" : s.view === "home" ? C.HOME_BG : COLORS.bg,
  );
  if (s.view === "sleep")
    return { lines: [], maxOffset: 0, total: 0, offset: 0 };
  if (s.view === "audioLink") {
    audioLink(c, s);
    if (s.volumeUntil > now) volume(c, s, s.linkLevel, s.linkMax);
    return { lines: [], maxOffset: 0, total: 0, offset: 0 };
  }
  header(c, s);
  let layout = { lines: [], maxOffset: 0, total: 0, offset: 0 };
  if (s.view === "main") {
    const i = getIcons();
    layout = textWindow(s, measureFor(c, L.FONT_BODY));
    const alpha = s.footerOpacity ?? footerOpacity ?? (layout.expanded ? 0 : 1);
    if (s.hasContext) c.drawImage(i.circle, 6, 88);
    c.drawImage(i.line, 6, 110);
    c.globalAlpha = alpha;
    if (s.hasContext) text(c, T.new_conversation, 28, 102, 16, COLORS.white);
    text(c, s.channelNames?.[s.channelIndex] ?? T.default_channel, L.CONTENT_X, 124, 16, COLORS.white, L.CONTENT_WIDTH);
    c.globalAlpha = 1;
    c.drawImage(
      ["listening", "recordCheck"].includes(s.phase) ? i.frame : i.ptt,
      L.LEFT,
      L.PTT_Y,
    );
    if (["listening", "recordCheck"].includes(s.phase)) {
      rect(c, L.METER_X, L.METER_Y, L.METER_WIDTH, L.METER_HEIGHT, C.METER_BG);
      const height = pttBarHeight(s);
      rect(
        c,
        L.METER_X,
        L.METER_Y + L.METER_HEIGHT - height,
        L.METER_WIDTH,
        height,
        C.METER,
      );
    }
    c.save();
    c.beginPath();
    c.rect(
      L.CONTENT_X,
      L.CONTENT_TOP,
      L.CONTENT_WIDTH,
      (layout.expanded ? L.HEIGHT : L.COMPACT_BOTTOM) - L.CONTENT_TOP,
    );
    c.clip();
    layout.lines
      .slice(0, layout.expanded ? L.EXPANDED_ROWS : L.COMPACT_ROWS)
      .forEach((line, row) => {
        c.globalAlpha = row >= 2 ? 1 - alpha : 1;
        text(
          c,
          line,
          L.CONTENT_X,
          L.TEXT_BASELINE + row * L.LINE_HEIGHT,
          L.FONT_BODY,
          row === 0 && layout.offset === 0 && title(s) === T.phase_listening
            ? COLORS.teal
            : COLORS.ink,
        );
      });
    c.restore();
    if (processingStage(s) > 1) {
      rect(c, 0, L.FOOTER_TOP, L.WIDTH, L.HEIGHT - L.FOOTER_TOP, C.WAIT_BG);
      rect(c, 0, L.FOOTER_TOP, L.WIDTH, 1, C.WAIT_EDGE);
      text(
        c,
        T.phase_thinking,
        L.LEFT,
        L.FOOTER_LABEL_Y,
        L.FOOTER_FONT,
        COLORS.white,
      );
      rect(
        c,
        L.LEFT,
        L.BAR_Y,
        processingWidth(s.processingElapsed),
        L.BAR_HEIGHT,
        C.WAIT_BAR,
      );
    }
  } else if (s.view === "home") {
    const labels = s.homeLabels;
    const selected = Math.min(s.selected, labels.length - 1);
    const visible = L.MENU_VISIBLE_ROWS;
    const first = Math.max(
      0,
      Math.min(selected - visible + 1, labels.length - visible),
    );
    const activeRow = selected - first;
    rect(
      c,
      L.LEFT,
      L.MENU_TOP + activeRow * L.MENU_ROW_HEIGHT,
      L.RIGHT - L.LEFT,
      L.MENU_ROW_HEIGHT,
      COLORS.orange,
    );
    labels.slice(first, first + visible).forEach((item, row) =>
      text(
        c,
        item,
        L.MENU_TEXT_X,
        L.MENU_BASELINE + row * L.MENU_ROW_HEIGHT,
        L.MENU_FONT,
        row === activeRow ? COLORS.white : COLORS.ink,
        L.RIGHT - L.MENU_TEXT_X - 4,
      ),
    );
    if (labels.length > visible) {
      const trackY = L.MENU_TOP;
      const trackH = visible * L.MENU_ROW_HEIGHT;
      const thumbH = Math.max(12, Math.round((trackH * visible) / labels.length));
      const thumbY =
        trackY +
        Math.round(((trackH - thumbH) * first) / (labels.length - visible));
      rect(c, 156, trackY, 2, trackH, "#223236");
      rect(c, 156, thumbY, 2, thumbH, "#7b8d89");
    }
    rect(
      c,
      0,
      L.STATUS_FOOTER_TOP,
      L.WIDTH,
      L.HEIGHT - L.STATUS_FOOTER_TOP,
      C.WAIT_BG,
    );
    rect(c, 0, L.STATUS_FOOTER_TOP, L.WIDTH, 1, C.WAIT_EDGE);
    text(
      c,
      T.menu_enter,
      L.STATUS_HINT_X,
      L.STATUS_FOOTER_Y,
      L.STATUS_HINT_FONT,
      COLORS.ink,
      L.STATUS_HINT_EXIT_X - L.STATUS_HINT_X,
    );
    font(c, L.STATUS_HINT_FONT);
    const switchTextX = L.RIGHT - c.measureText(T.switch_feature).width;
    const arrowGap =
      L.STATUS_HINT_TEXT_X - L.STATUS_HINT_X - L.STATUS_ARROW_WIDTH;
    const switchArrowX = Math.round(
      switchTextX - arrowGap - L.STATUS_ARROW_WIDTH,
    );
    for (const [x, y, w, h] of GRAPHICS.pageArrows)
      rect(c, switchArrowX + x, L.STATUS_ARROW_Y + y, w, h, COLORS.orangeText);
    text(
      c,
      T.switch_feature,
      switchTextX,
      L.STATUS_FOOTER_Y,
      L.STATUS_HINT_FONT,
      COLORS.ink,
    );
  } else if (s.view === "ble") {
    const details = {
      starting: T.ble_test_only,
      ready: "使用小程序‘喂喂有回音’连接",
      connected: T.ble_send_test,
      passed: T.ble_utf8_ok,
      failed: "请重试或检查蓝牙",
      unavailable: "请检查设备蓝牙",
      expired: "按 MENU 重新开启",
      pair: "确认后授权后台管理",
      approved: T.wifi_phone,
      connecting: T.wifi_wait,
      success: T.wifi_local,
      auth: T.wifi_failure_hint,
      timeout: T.wifi_failure_hint,
      cancelled: T.wifi_failure_hint,
      wifiFailed: T.wifi_failure_hint,
    };
    const states = {
      pair: "核对确认码",
      approved: T.wifi_approved,
      connecting: T.wifi_connecting,
      success: T.wifi_success,
      auth: T.wifi_auth,
      timeout: T.wifi_timeout,
      cancelled: T.wifi_cancelled,
      wifiFailed: T.wifi_failed,
    };
    const action =
      s.policyAction === 2
        ? T.manager_revoke
        : s.background
          ? T.manager_disable
          : T.manager_enable;
    const headline = s.policyAction
      ? action
      : states[s.bleStatus] || T["ble_" + s.bleStatus];
    const isCode = !s.policyAction && s.bleStatus === "pair";
    const isSuccess =
      !s.policyAction &&
      (s.bleStatus === "success" || s.bleStatus === "passed");
    const isWifiFlow =
      !s.policyAction &&
      ["approved", "connecting", "auth", "timeout", "cancelled", "wifiFailed"].includes(
        s.bleStatus,
      );
    const subline = isCode
      ? s.pairingCode
      : s.bleStatus === "success" && !s.policyAction
        ? s.ip
          ? "IP " + s.ip
          : T.wifi_local
        : isWifiFlow
          ? details[s.bleStatus]
          : s.bleName;
    const subColor = isCode
      ? COLORS.orangeText
      : isSuccess
        ? COLORS.teal
        : isWifiFlow
          ? COLORS.ink
          : COLORS.orangeText;
    const note = s.policyAction
      ? "上下切换其他选项"
      : isWifiFlow
        ? s.bleName
        : s.bleStatus === "success" && !s.ip
          ? s.bleName
          : details[s.bleStatus];

    text(c, headline, L.STATUS_TITLE_X, 47, 18, C.PAGE_TITLE, 144);
    text(c, subline, L.STATUS_TITLE_X, 71, isCode ? 15 : 14, subColor, 144);
    let noteSize = 13;
    font(c, noteSize);
    while (noteSize > 10 && c.measureText(note).width > 146) {
      noteSize -= 1;
      font(c, noteSize);
    }
    text(c, note, L.STATUS_TITLE_X, 93, noteSize, "#9eb0aa", 146);

    rect(
      c,
      0,
      L.STATUS_FOOTER_TOP,
      L.WIDTH,
      L.HEIGHT - L.STATUS_FOOTER_TOP,
      C.WAIT_BG,
    );
    rect(c, 0, L.STATUS_FOOTER_TOP, L.WIDTH, 1, C.WAIT_EDGE);

    const retryable = ["expired", "failed", "unavailable"].includes(s.bleStatus);
    if (s.policyAction) {
      text(
        c,
        "MENU 确认",
        L.STATUS_HINT_X,
        L.STATUS_FOOTER_Y,
        L.STATUS_HINT_FONT,
        COLORS.ink,
        L.STATUS_HINT_EXIT_X - L.STATUS_HINT_X,
      );
    } else if (s.bleStatus === "pair") {
      text(
        c,
        "MENU 授权",
        L.STATUS_HINT_X,
        L.STATUS_FOOTER_Y,
        L.STATUS_HINT_FONT,
        COLORS.ink,
        L.STATUS_HINT_EXIT_X - L.STATUS_HINT_X,
      );
    } else if (retryable) {
      text(
        c,
        "MENU 重试",
        L.STATUS_HINT_X,
        L.STATUS_FOOTER_Y,
        L.STATUS_HINT_FONT,
        COLORS.ink,
        L.STATUS_HINT_EXIT_X - L.STATUS_HINT_X,
      );
    } else if (s.hasBinding) {
      for (const [x, y, w, h] of GRAPHICS.pageArrows)
        rect(c, L.STATUS_HINT_X + x, L.STATUS_ARROW_Y + y, w, h, COLORS.orangeText);
      text(
        c,
        "选项",
        L.STATUS_HINT_TEXT_X,
        L.STATUS_FOOTER_Y,
        L.STATUS_HINT_FONT,
        COLORS.ink,
        L.STATUS_HINT_EXIT_X - L.STATUS_HINT_TEXT_X,
      );
    }
    const rightHint = s.policyAction ? "EXIT 取消" : T.status_exit_key;
    font(c, L.STATUS_HINT_FONT);
    text(
      c,
      rightHint,
      L.RIGHT - c.measureText(rightHint).width,
      L.STATUS_FOOTER_Y,
      L.STATUS_HINT_FONT,
      COLORS.ink,
      L.WIDTH - L.STATUS_HINT_EXIT_X,
    );
  } else if (s.view === "external") {
    text(c, "系统应用", 8, 49, 18, COLORS.white);
    text(c, "不模拟厂家系统界面", 8, 73, 14, COLORS.ink);
    text(c, "返回查看 NeoTalkie 桌面", 8, 100, 14, COLORS.ink);
  } else {
    let heading = "",
      rows = [],
      counter = "";
    if (s.view === "menu") {
      const first =
        Math.floor(s.selected / L.MENU_VISIBLE_ROWS) * L.MENU_VISIBLE_ROWS;
      rect(
        c,
        L.LEFT,
        L.MENU_TOP + (s.selected % L.MENU_VISIBLE_ROWS) * L.MENU_ROW_HEIGHT,
        L.RIGHT - L.LEFT,
        L.MENU_ROW_HEIGHT,
        COLORS.orange,
      );
      (s.owner === "launcher" ? DEVICE_MENU : MENU).slice(first, first + L.MENU_VISIBLE_ROWS).forEach((item, row) =>
        text(
          c,
          item.title,
          L.MENU_TEXT_X,
          L.MENU_BASELINE + row * L.MENU_ROW_HEIGHT,
          L.MENU_FONT,
          row === s.selected % L.MENU_VISIBLE_ROWS ? COLORS.white : COLORS.ink,
          L.RIGHT - L.MENU_TEXT_X,
        ),
      );
      if (MENU.length > L.MENU_VISIBLE_ROWS)
        counter = `${Math.floor(s.selected / L.MENU_VISIBLE_ROWS) + 1}/${Math.ceil(MENU.length / L.MENU_VISIBLE_ROWS)}`;
    } else if (s.view === "checks") {
      heading = T.text_143;
      rows = [s.checking, T.text_083, T.text_012];
    } else {
      const pages = pagesFor(s),
        page = Math.min(s.page, pages.length - 1);
      [heading, ...rows] = pages[page] || [""];
      if (pages.length > 1) counter = `${page + 1}/${pages.length}`;
    }
    text(
      c,
      heading,
      L.STATUS_TITLE_X,
      L.STATUS_TITLE_Y,
      L.STATUS_TITLE_FONT,
      C.PAGE_TITLE,
      s.view === "checks" ? 144 : 116,
    );
    rows.forEach((r, i) =>
      text(
        c,
        r,
        L.STATUS_TITLE_X,
        L.STATUS_ROW_Y + i * L.STATUS_ROW_HEIGHT,
        s.view === "checks" && i < 2 ? 16 : L.STATUS_ROW_FONT,
        s.view === "checks" && i === 0 ? COLORS.teal : COLORS.ink,
        144,
      ),
    );
    if (counter) numbers(c, counter, 132, 34, 2, COLORS.orangeText);
    rect(
      c,
      0,
      L.STATUS_FOOTER_TOP,
      L.WIDTH,
      L.HEIGHT - L.STATUS_FOOTER_TOP,
      C.WAIT_BG,
    );
    rect(c, 0, L.STATUS_FOOTER_TOP, L.WIDTH, 1, C.WAIT_EDGE);
    if (s.view === "menu") {
      text(
        c,
        T.menu_enter,
        L.STATUS_HINT_X,
        L.STATUS_FOOTER_Y,
        L.STATUS_HINT_FONT,
        COLORS.ink,
        L.STATUS_HINT_EXIT_X - L.STATUS_HINT_X,
      );
    } else if (s.view !== "checks") {
      for (const [x, y, w, h] of GRAPHICS.pageArrows)
        rect(c, L.STATUS_HINT_X + x, L.STATUS_ARROW_Y + y, w, h, COLORS.orangeText);
      text(
        c,
        T.status_page_keys,
        L.STATUS_HINT_TEXT_X,
        L.STATUS_FOOTER_Y,
        L.STATUS_HINT_FONT,
        COLORS.ink,
        L.STATUS_HINT_EXIT_X - L.STATUS_HINT_TEXT_X,
      );
    }
    font(c, L.STATUS_HINT_FONT);
    text(
      c,
      T.status_exit_key,
      L.RIGHT - c.measureText(T.status_exit_key).width,
      L.STATUS_FOOTER_Y,
      L.STATUS_HINT_FONT,
      COLORS.ink,
      L.WIDTH - L.STATUS_HINT_EXIT_X,
    );
  }
  if (s.volumeUntil > now && s.view !== "external")
    volume(c, s);
  return layout;
}
export function scaledScreen(source, scale = 4, grid = false) {
  const c = document.createElement("canvas");
  c.width = WIDTH * scale;
  c.height = HEIGHT * scale;
  const x = c.getContext("2d");
  x.imageSmoothingEnabled = false;
  x.drawImage(source, 0, 0, c.width, c.height);
  if (grid && scale >= 3) {
    x.fillStyle = "#00000036";
    for (let i = 0; i < c.width; i += scale)
      x.fillRect(i + scale - 1, 0, 1, c.height);
    for (let i = 0; i < c.height; i += scale)
      x.fillRect(0, i + scale - 1, c.width, 1);
  }
  return c;
}
export function deviceSVG(screenURL, volume = 1) {
  return `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="420" height="680" viewBox="0 0 420 680">
<defs><linearGradient id="case" x2="1" y2="1"><stop stop-color="#353b3b"/><stop offset=".45" stop-color="#232929"/><stop offset="1" stop-color="#141b1b"/></linearGradient><linearGradient id="rubber"><stop stop-color="#0e1515"/><stop offset=".5" stop-color="#272e2e"/><stop offset="1" stop-color="#0b1010"/></linearGradient><linearGradient id="edge"><stop stop-color="#69716e"/><stop offset=".15" stop-color="#313a37"/><stop offset="1" stop-color="#111817"/></linearGradient><pattern id="grain" width="5" height="5" patternUnits="userSpaceOnUse"><circle cx="1" cy="1" r=".55" fill="#ffffff" opacity=".04"/><circle cx="3" cy="4" r=".5" fill="#000" opacity=".3"/></pattern></defs>
<ellipse cx="234" cy="655" rx="155" ry="15" fill="#102c2720"/>
<rect x="112" y="6" width="41" height="144" rx="20" fill="url(#rubber)" stroke="#101817" stroke-width="3"/>
<rect x="294" y="78" width="74" height="54" rx="10" fill="url(#rubber)" stroke="#151b1b" stroke-width="3"/>
${Array.from({ length: 10 }, (_, i) => `<path d="M${299 + i * 7} 86v39" stroke="#535b55" opacity=".4"/>`).join("")}
<path d="M331 85v18" stroke="#54c8b0" stroke-width="3" transform="rotate(${(volume - 2.5) * 12} 331 106)"/>
<path d="M60 154Q38 164 34 192L27 567Q30 613 67 633L97 626V141Z" fill="url(#edge)" stroke="#101716" stroke-width="3"/>
<rect x="61" y="119" width="349" height="530" rx="48" fill="url(#case)" stroke="#111918" stroke-width="5"/>
<rect x="66" y="124" width="339" height="520" rx="43" fill="url(#grain)"/>
<text x="230" y="151" text-anchor="middle" font-family="sans-serif" font-size="14" font-weight="700" letter-spacing="3" fill="#aeb9ae">NEOTALKIE · TALK</text>
${[0, 1, 2].map((i) => `<rect x="96" y="${165 + i * 22}" width="278" height="15" rx="7.5" fill="#080e0d" stroke="#3e4943" stroke-width="2"/><path d="M111 ${168 + i * 22}h247" stroke="#5d685c" opacity=".28"/>`).join("")}
<rect x="71" y="236" width="338" height="275" rx="17" fill="#090f0e" stroke="#58615a" stroke-width="2"/>
<image x="80" y="245" width="320" height="256" href="${screenURL}" style="image-rendering:pixelated"/>
<rect x="20" y="229" width="34" height="142" rx="17" fill="#101a18" stroke="#42b49a" stroke-width="4"/><rect x="34" y="259" width="6" height="81" rx="3" fill="#42b49a"/>
<circle cx="37" cy="405" r="19" fill="#18211f" stroke="#050c0a" stroke-width="3"/><circle cx="37" cy="405" r="6" fill="none" stroke="#97a39b" stroke-width="3"/>
<circle cx="37" cy="457" r="19" fill="#18211f" stroke="#050c0a" stroke-width="3"/><path d="M37 450v14" stroke="#97a39b" stroke-width="3"/>
${[
  [91, 526, 94, 42, "MENU"],
  [298, 526, 94, 42, "EXIT"],
  [91, 575, 94, 42, "⌁"],
  [298, 575, 94, 42, "⏻"],
]
  .map(
    ([x, y, w, h, t]) =>
      `<rect x="${x}" y="${y}" width="${w}" height="${h}" rx="7" fill="url(#rubber)" stroke="#0b1110" stroke-width="3"/><text x="${x + w / 2}" y="${y + 27}" text-anchor="middle" font-family="sans-serif" font-size="${t.length > 1 ? 13 : 23}" fill="${t === "⏻" ? "#d46c6c" : t === "⌁" ? "#63be92" : "#bdc9bd"}">${t}</text>`,
  )
  .join("")}
<rect x="200" y="523" width="82" height="96" rx="9" fill="url(#rubber)" stroke="#0b1110" stroke-width="3"/>
<path d="M227 550l14-12 14 12Z M227 593l14 12 14-12Z" fill="#bec9b4"/><path d="M210 571h62" stroke="#0b1110" stroke-width="3"/>
</svg>`;
}
