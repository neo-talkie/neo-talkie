import { PRESETS, preset } from "../model.js";

export const apps = {
  talk: { name: "对讲", package: "com.neotalkie.talk" },
  launcher: { name: "手台桌面", package: "com.neotalkie.launcher" },
  audio: { name: "音频互联", package: "com.neotalkie.hfpmic" },
};

function nativePage(scene) {
  const view = scene.view || "main";
  if (view === "audioLink") return ["audio", "AudioLinkActivity"];
  if (view === "ble") return ["launcher", "BleSetupActivity"];
  if (view === "home") return ["launcher", "HomeActivity"];
  if (view === "device" || scene.owner === "launcher") return ["launcher", "StatusActivity"];
  if (view === "menu") return ["talk", "TalkMenuActivity"];
  if (["network", "checks", "results", "diagnostics", "checkError"].includes(view))
    return ["talk", "ServiceStatusActivity"];
  if (view === "sleep" || view === "main") return ["talk", "MainActivity"];
  throw Error("Missing native page for " + scene.id + ": " + view);
}

export const scenes = PRESETS.map(scene => {
  const [app, activity] = nativePage(scene);
  let group = scene.group;
  if (app === "audio") group = scene.linkPage === "modes" ? "讲话模式" : "互联首页";
  return { id: scene.id, name: scene.name, note: scene.note, group, app, activity };
});

export function fixture(scene) {
  return { package: apps[scene.app].package, activity: scene.activity, state: preset(scene.id) };
}
export async function fingerprint(value) {
  const bytes = new TextEncoder().encode(JSON.stringify(value));
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return Array.from(new Uint8Array(hash), n => n.toString(16).padStart(2, "0")).join("");
}
