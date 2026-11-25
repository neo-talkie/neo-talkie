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
  lime: C.ACCENT,
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
function volume(c, s) {
  rect(c, 0, L.FOOTER_TOP, L.WIDTH, L.HEIGHT - L.FOOTER_TOP, C.VOLUME_BG);
  rect(c, 0, L.FOOTER_TOP, L.WIDTH, 1, COLORS.lime);
  text(c, T.volume, L.LEFT, L.FOOTER_LABEL_Y, L.FOOTER_FONT, COLORS.lime);
  for (let i = 0; i < 5; i++)
    rect(
      c,
      Math.round(6 + i * 22.8),
      111,
      Math.round(6 + (i + 1) * 22.8) - 3 - Math.round(6 + i * 22.8),
      12,
      i < s.volume ? COLORS.lime : C.VOLUME_EMPTY,
    );
  numbers(c, `${s.volume}/5`, 132, 112, 2, C.PAGE_TITLE);
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
    text(c, T.default_channel, 28, 124, 16, COLORS.white);
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
    const selected = Math.min(s.selected, s.homeLabels.length - 1);
    rect(c, 56, 31, 48, 48, C.HOME_ICON);
    if (selected === 0)
      [8, 16, 12, 4].forEach((h, i) =>
        rect(c, 58 + i * 12, 39 + (16 - h), 8, h * 2, COLORS.lime),
      );
    else {
      // ROM-owned icons are intentionally marked as placeholders.
      round(c, 64, 39, 32, 32, 5, "#82948e", 2);
      text(c, "?", 74, 63, 24, COLORS.white);
    }
    const label = ellipsis(s.homeLabels[selected], measureFor(c, 22), 150);
    font(c, 22);
    text(
      c,
      label,
      (160 - c.measureText(label).width) / 2,
      98,
      22,
      COLORS.white,
    );
    numbers(
      c,
      `${selected + 1}/${s.homeLabels.length}`,
      132,
      33,
      2,
      COLORS.lime,
    );
    rect(
      c,
      0,
      L.STATUS_FOOTER_TOP,
      L.WIDTH,
      L.HEIGHT - L.STATUS_FOOTER_TOP,
      C.VOLUME_BG,
    );
    text(c, T.text_009, 16, 122, 14, COLORS.lime);
  } else if (s.view === "ble") {
    const details={starting:T.ble_test_only,ready:T.ble_scan,connected:T.ble_send_test,passed:T.ble_utf8_ok,failed:T.ble_retry,unavailable:T.ble_retry,expired:T.ble_retry,pair:T.wifi_confirm,approved:T.wifi_phone,connecting:T.wifi_wait,success:T.wifi_local,auth:T.wifi_failure_hint,timeout:T.wifi_failure_hint,cancelled:T.wifi_failure_hint,wifiFailed:T.wifi_failure_hint};
    const states={pair:T.wifi_pair,approved:T.wifi_approved,connecting:T.wifi_connecting,success:T.wifi_success,auth:T.wifi_auth,timeout:T.wifi_timeout,cancelled:T.wifi_cancelled,wifiFailed:T.wifi_failed};
    text(c,T.ble_title,6,47,18,C.PAGE_TITLE);
    text(c,states[s.bleStatus] ? s.pairingCode : s.bleName,6,65,14,C.ACCENT);
    text(c,states[s.bleStatus] || T["ble_"+s.bleStatus],6,84,16,C.INK);
    text(c,details[s.bleStatus],6,102,12,C.INK);
    rect(c,0,108,160,20,C.VOLUME_BG);
    text(c,s.bleStatus === "expired" ? T.ble_retry : T.ble_exit,6,123,12,C.ACCENT);
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
        C.SELECTION,
      );
      (s.owner === "launcher" ? DEVICE_MENU : MENU).slice(first, first + L.MENU_VISIBLE_ROWS).forEach((item, row) =>
        text(
          c,
          item.title,
          L.MENU_TEXT_X,
          L.MENU_BASELINE + row * L.MENU_ROW_HEIGHT,
          L.MENU_FONT,
          C.PAGE_TITLE,
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
        s.view === "checks" && i === 0 ? C.CHECKING : COLORS.ink,
        144,
      ),
    );
    if (counter) numbers(c, counter, 132, 34, 2, COLORS.lime);
    rect(
      c,
      0,
      L.STATUS_FOOTER_TOP,
      L.WIDTH,
      L.HEIGHT - L.STATUS_FOOTER_TOP,
      C.VOLUME_BG,
    );
    if (s.view === "menu") {
      text(
        c,
        T.menu_enter,
        L.STATUS_HINT_X,
        L.STATUS_FOOTER_Y,
        L.STATUS_HINT_FONT,
        COLORS.lime,
        L.STATUS_HINT_EXIT_X - L.STATUS_HINT_X,
      );
    } else if (s.view !== "checks") {
      for (const [x, y, w, h] of GRAPHICS.pageArrows)
        rect(c, L.STATUS_HINT_X + x, L.STATUS_ARROW_Y + y, w, h, COLORS.lime);
      text(
        c,
        T.status_page_keys,
        L.STATUS_HINT_TEXT_X,
        L.STATUS_FOOTER_Y,
        L.STATUS_HINT_FONT,
        COLORS.lime,
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
      COLORS.lime,
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
