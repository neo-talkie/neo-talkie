import { apps, scenes, fixture, fingerprint } from "./catalog.js";
import { renderScreen } from "../render.js";
const $ = id => document.getElementById(id);
const create = (tag, value) => { const el = document.createElement(tag); el.textContent = value; return el; };
const refURL = id => "/studio/baseline/" + encodeURIComponent(id) + ".png";
const appMarks = { talk: "◉", launcher: "▦", audio: "≋" };
const request = req => new Promise((ok, fail) => { req.onsuccess = () => ok(req.result); req.onerror = () => fail(req.error); });
const database = indexedDB.open("neotalkie-studio-captures", 1);
database.onupgradeneeded = () => database.result.createObjectStore("captures", { keyPath: "id" });
const db = await request(database).catch(() => null);
const local = db ? await request(db.transaction("captures").objectStore("captures").getAll()) : [];
const json = async path => { const r = await fetch(path); if (!r.ok) throw Error("无法读取 " + path); return r.json(); };
let refs, records, selection, detailGeneration = 0;
const ids = new Map(scenes.map(s => [s.id, s]));
const digest = new Map();

function available(id) { return records.has(id); }
function filtered() {
  const query = $("search").value.trim().toLowerCase();
  return scenes.filter(s => (!$("captured").checked || available(s.id)) &&
    [apps[s.app].name, apps[s.app].package, s.name, s.id, s.group, s.note].join(" ").toLowerCase().includes(query));
}
function setScene(id, scroll = false) {
  selection = id;
  history.replaceState(null, "", "#" + encodeURIComponent(id));
  render();
  if (scroll) $("detail").scrollIntoView({ block: "start" });
}
function render() {
  const choices = filtered();
  if (!choices.some(s => s.id === selection)) selection = choices[0]?.id;
  if (selection) history.replaceState(null, "", "#" + encodeURIComponent(selection));
  $("count").textContent = Object.keys(apps).length + " 个应用 · " + choices.length + " 个场景";
  $("list").replaceChildren();
  for (const [key, item] of Object.entries(apps)) {
    const appScenes = choices.filter(s => s.app === key);
    if (!appScenes.length) continue;
    const appSection = create("section", "");
    appSection.className = "app-section";
    appSection.id = "app-" + key;
    appSection.dataset.appSection = key;
    const head = create("div", ""), left = create("div", ""), titleWrap = create("div", "");
    head.className = "app-head"; titleWrap.className = "app-title";
    const mark = create("span", appMarks[key] || "");
    mark.className = "mark"; mark.setAttribute("aria-hidden", "true");
    titleWrap.append(mark, create("h2", item.name));
    left.append(titleWrap, create("p", item.package));
    head.append(left, create("span", appScenes.length + " 个场景"));
    appSection.append(head);
    const groups = new Map();
    for (const s of appScenes) {
      if (!groups.has(s.group)) {
        const section = create("section", ""), grid = create("div", "");
        section.className = "group"; grid.className = "cards";
        section.append(create("h3", s.group), grid); appSection.append(section); groups.set(s.group, grid);
      }
      const btn = create("button", ""), img = new Image();
      btn.className = "card"; btn.setAttribute("aria-current", s.id === selection);
      img.loading = "lazy"; img.alt = ""; img.width = 160; img.height = 128;
      if (refs[s.id]) img.src = refURL(s.id);
      btn.append(img, create("b", s.name));
      if (available(s.id)) btn.append(create("small", "有实图"));
      btn.onclick = () => setScene(s.id, true);
      groups.get(s.group).append(btn);
    }
    $("list").append(appSection);
  }
  if (!choices.length) $("list").append(Object.assign(create("p", "没有符合条件的场景"), {className:"blank"}));
  $("detail").hidden = !selection;
  const generation = ++detailGeneration;
  if (selection) showDetail(ids.get(selection), generation).catch(e => { if (generation === detailGeneration) $("error").textContent = e.message; });
}
async function showDetail(s, generation) {
  const input = fixture(s);
  if (!digest.has(s.id)) digest.set(s.id, fingerprint(input));
  const stamp = await digest.get(s.id);
  if (generation !== detailGeneration) return;
  const ref = refs[s.id], record = records.get(s.id);
  $("scene-name").textContent = s.name; $("scene-id").textContent = s.id;
  $("location").textContent = apps[s.app].name + " / " + s.group;
  $("scene-note").textContent = s.note; $("parameters").textContent = JSON.stringify(input, null, 2);
  $("parameters").hidden = true; $("inputs").setAttribute("aria-expanded", false);
  if (ref) $("reference").src = refURL(s.id);
  else {
    const canvas = document.createElement("canvas"); renderScreen(canvas, input.state, 0);
    $("reference").src = canvas.toDataURL();
  }
  $("actual").hidden = !record; $("pending").hidden = !!record; $("compare").hidden = !record || !ref;
  $("overlay").hidden = true; $("compare").textContent = "叠加比对";
  $("provenance").hidden = !record; $("actual-tag").textContent = record ? record.version : "";
  if (record) {
    $("actual").src = record.image;
    $("provenance").textContent = [record.source, "应用 " + record.version, record.date, record.note].filter(Boolean).join(" · ");
  } else $("actual").removeAttribute("src");
  const warnings = [];
  if (!ref) warnings.push("当前为设计草稿，尚未冻结基准。");
  else if (ref.input !== stamp) warnings.push("当前场景参数已变更，左侧保留上次设计基准。");
  if (record && (record.input !== stamp || record.revision !== ref?.revision)) warnings.push("此实图对应较早的参数或设计基准。");
  $("stale").hidden = !warnings.length; $("stale").textContent = warnings.join(" ");
}
function followHash() {
  const s = ids.get(decodeURIComponent(location.hash.slice(1))) || scenes[0];
  selection = s.id; $("search").value = ""; $("captured").checked = false; render();
}
$("search").oninput = render; $("captured").onchange = render; window.onhashchange = followHash;
$("inputs").onclick = () => { $("parameters").hidden = !$("parameters").hidden; $("inputs").setAttribute("aria-expanded", !$("parameters").hidden); };
$("compare").onclick = () => {
  $("overlay").hidden = !$("overlay").hidden; $("compare").textContent = $("overlay").hidden ? "叠加比对" : "收起叠加";
  $("layer-ref").src = $("reference").src; $("layer-actual").src = $("actual").src;
  $("layer-actual").style.opacity = $("opacity").value / 100;
};
$("opacity").oninput = () => { $("layer-actual").style.opacity = $("opacity").value / 100; };
$("import").onclick = () => {
  $("form").reset(); $("form-error").textContent = ""; $("import-for").textContent = ids.get(selection).name + " · " + selection;
  $("dialog").showModal();
};
$("cancel").onclick = () => $("dialog").close();
$("form").onsubmit = async event => {
  event.preventDefault();
  const form = event.currentTarget, save = form.querySelector("[type=submit]"), id = selection;
  save.disabled = true;
  let bitmap;
  try {
    if (!db) throw Error("当前浏览器无法保存本地实图。");
    const file = form.elements.file.files[0];
    const sig = new Uint8Array(await file.slice(0, 8).arrayBuffer());
    if (sig.join() !== "137,80,78,71,13,10,26,10" || file.size > 4000000) throw Error("请选择手台 PNG 截图。");
    bitmap = await createImageBitmap(file);
    const rotate = form.elements.rotation.value;
    const w = rotate === "0" ? 160 : 128, h = rotate === "0" ? 128 : 160;
    if (bitmap.width !== w || bitmap.height !== h) throw Error("截图尺寸或方向与选择不符，需要 " + w + " × " + h + "。");
    const canvas = document.createElement("canvas"); canvas.width = 160; canvas.height = 128;
    const ctx = canvas.getContext("2d");
    if (rotate === "left") { ctx.translate(0, 128); ctx.rotate(-Math.PI / 2); }
    if (rotate === "right") { ctx.translate(160, 0); ctx.rotate(Math.PI / 2); }
    ctx.drawImage(bitmap, 0, 0);
    const version = form.elements.version.value.trim(), source = form.elements.source.value.trim();
    if (!version || !source) throw Error("请填写应用版本和采集来源。");
    const entry = { id, version, source, date: new Date().toISOString().slice(0, 10),
      input: await digest.get(id), revision: refs[id]?.revision || "", image: canvas.toDataURL() };
    const tx = db.transaction("captures","readwrite");
    tx.objectStore("captures").put(entry);
    await new Promise((ok, fail) => { tx.oncomplete = ok; tx.onabort = () => fail(tx.error); });
    records.set(id, entry); $("dialog").close(); render();
  } catch (e) { $("form-error").textContent = e.message; }
  finally { bitmap?.close(); save.disabled = false; }
};
try {
  const [baseline, actuals] = await Promise.all([json("/studio/baseline/index.json"), json("/actual/index.json")]);
  refs = baseline;
  records = new Map(Object.entries(actuals)); for (const item of local) records.set(item.id, item);
  for (const [key, item] of Object.entries(apps)) {
    const btn = create("button", ""), mark = create("span", appMarks[key] || "");
    mark.className = "mark"; mark.setAttribute("aria-hidden","true");
    btn.dataset.app = key; btn.append(mark, create("span", item.name), Object.assign(create("span", scenes.filter(s => s.app === key).length), {className:"count"}));
    btn.onclick = () => {
      if ($("search").value || $("captured").checked) { $("search").value = ""; $("captured").checked = false; render(); }
      $("app-" + key)?.scrollIntoView({ behavior: "smooth", block: "start" });
    };
    $("apps").append(btn);
  }
  followHash();
} catch (e) { $("error").textContent = e.message; }
