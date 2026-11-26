// Verify the vendored design runtime without accessing a sibling checkout.
import { readFile } from "node:fs/promises";
import { createHash } from "node:crypto";
import { apps, scenes, fixture, fingerprint } from "../simulator/studio/catalog.js";
const root = new URL("../simulator/", import.meta.url);
const release = JSON.parse(await readFile(new URL("studio-release.json",root)));
for (const [file, sum] of Object.entries(release.files)) {
  const data = await readFile(new URL(file,root));
  if (createHash("sha256").update(data).digest("hex") !== sum) throw Error("Design Studio release drift: "+file);
}
const refs = JSON.parse(await readFile(new URL("studio/baseline/index.json",root)));
for (const scene of scenes) {
  if (refs[scene.id]?.input !== await fingerprint(fixture(scene))) throw Error("Design reference needs review: "+scene.id);
}
if (Object.keys(refs).length !== scenes.length) throw Error("Unexpected design reference");
// A new foreground Activity must appear in the design catalog for its own APK.
for (const [app, folder] of Object.entries({talk:"talk", launcher:"launcher", audio:"audio-link"})) {
  const manifest = await readFile(new URL("../../android/"+folder+"/AndroidManifest.xml",root), "utf8");
  const activities = new Set(Array.from(manifest.matchAll(/<activity\s[^>]*?android:name="([^"]+)"/gs),
    match => match[1].split(".").at(-1)));
  const mapped = new Set(scenes.filter(s=>s.app===app).map(s=>s.activity));
  if (!manifest.includes('package="'+apps[app].package+'"') || !activities.size ||
      !Array.from(activities).every(name=>mapped.has(name)) || !Array.from(mapped).every(name=>activities.has(name)))
    throw Error("Native Activity and design catalog differ for "+folder);
}
console.log(release.package, release.version, scenes.length, "design references verified");
