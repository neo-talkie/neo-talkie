// App-owned page templates, mirroring StatusActivity.
// All values are synthetic fixtures; no hardware, credentials or service access.
import {
  VIEWS,
  SERVICES,
  CHECK_ERRORS,
  T,
  ANDROID_BASELINE,
} from "./generated/ui-contract.js";
export { VIEWS, SERVICES, CHECK_ERRORS };
export const DEVICE = {
  model: "mx_b5",
  android: "5.1.1",
  version: ANDROID_BASELINE,
  batteryPresent: true,
  full: false,
  plugged: false,
  temperature: 28,
  availableMemory: 146,
  totalMemory: 395,
  storage: 782,
  uptime: 136,
  configured: true,
};
export const NETWORK = {
  type: "wifi",
  typeName: "ETHERNET",
  mobile: false,
  wifiInfo: true,
  ssid: "测试网络",
  ip: "10.0.0.84",
  gateway: "10.0.0.1",
  rssi: -42,
  speed: 72,
  frequency: 2417,
};
export const SUCCESS = SERVICES.map((name, i) => ({
  name,
  success: true,
  message: i === 1 ? T.text_049 : T.text_139,
  detail: [T.text_064, T.text_062, T.text_063][i],
  elapsedMs: [214, 244, 146][i],
}));
export function networkType(s) {
  return s.network.type === "wifi" && s.wifi < 0 ? "offline" : s.network.type;
}
export function pagesFor(s) {
  const d = s.device,
    n = s.network,
    type = networkType(s);
  if (s.view === "diagnostics") {
    const network = pagesFor({ ...s, view: "network" });
    const result = pagesFor({ ...s, view: "results" });
    return s.stageAt
      ? [[T.network_test, s.checking, T.text_083, T.text_012], ...network]
      : [result[0], ...network, ...result.slice(1)];
  }
  if (s.view === "device")
    return [
      [
        T.system_status,
        `${T.text_113}${d.model}`,
        `${T.text_028}${d.android}`,
        `${T.text_037}${d.version}`,
      ],
      ...(d.batteryPresent
        ? [
            [
              T.battery_status,
              `${T.text_089}${s.battery < 0 ? T.text_065 : s.battery + "%"}`,
              d.full
                ? T.text_034
                : s.charging || d.plugged
                  ? T.text_035
                  : T.text_087,
              `${T.text_086}${Number(d.temperature).toFixed(1)}°C`,
            ],
          ]
        : []),
      [
        T.text_026,
        `${T.text_016}${d.availableMemory}MB`,
        `${T.text_046}${d.totalMemory}MB`,
        `${T.text_017}${d.storage}MB`,
      ],
      [
        T.text_133,
        `${T.text_025}${s.volume}/5`,
        `${T.text_039}${d.uptime}${T.text_001}`,
        s.owner === "launcher" ? T.device_settings : (d.configured ? T.text_119 : T.text_120),
      ],
    ];
  if (s.view === "network")
    return [
      [
        T.network_status,
        type === "offline" ? T.offline : T.text_105,
        type === "offline"
          ? T.text_130
          : type === "wifi"
            ? T.text_041
            : `${T.text_040}${type === "mobile" ? "MOBILE" : n.typeName}`,
        n.mobile ? T.text_098 : T.text_099,
      ],
      ...(type === "wifi" && n.wifiInfo
        ? [
            [T.text_006, n.ssid, `IP ${n.ip}`, `${T.text_104}${n.gateway}`],
            [
              T.text_007,
              `${T.text_014}${n.rssi} dBm`,
              `${T.text_145}${n.speed} Mbps`,
              `${T.text_150}${n.frequency} MHz`,
            ],
          ]
        : []),
    ];
  if (s.view === "checkError")
    return [[T.text_076, T.text_140, T.text_126, T.text_018]];
  if (s.view === "results")
    return s.results.length
      ? [
          [
            T.text_077,
            ...s.results.map(
              (r) => r.name + (r.success ? T.text_004 : T.text_003),
            ),
          ],
          ...s.results.map((r) => [
            r.name,
            r.message,
            `${T.text_109}${r.elapsedMs} ms`,
            r.detail,
          ]),
        ]
      : [[T.text_077, T.text_031, T.text_136, T.text_073]];
  return [];
}
