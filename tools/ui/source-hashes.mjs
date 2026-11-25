import { readFile, readdir } from "node:fs/promises";
import { createHash } from "node:crypto";
const root = new URL("../../", import.meta.url);
export async function sourceHashes() {
  const files = ["config/radio-ui/contract.json", "config/radio-ui/scenarios.json"];
  async function include(dir) {
    for (const entry of await readdir(new URL(dir + "/", root), { withFileTypes: true })) {
      if (["build", "tests", "scripts", "generated"].includes(entry.name)) continue;
      const path = dir + "/" + entry.name;
      if (entry.isDirectory()) await include(path);
      else if (/\.(java|js|mjs|css|html|xml|ttf|woff2|svg)$/.test(entry.name)) files.push(path);
    }
  }
  for (const dir of ['android/talk', 'android/launcher', 'android/common', 'tools/simulator']) await include(dir);
  const hashes = {};
  for (const file of files.sort()) hashes[file] = createHash('sha256').update(await readFile(new URL(file, root))).digest('hex');
  return hashes;
}
