import { createServer } from "node:http";
import { readFile, readdir } from "node:fs/promises";
import { resolve, extname } from "node:path";
import { fileURLToPath } from "node:url";
import { scenes } from "./studio/catalog.js";

const root = fileURLToPath(new URL(".", import.meta.url));
const captures = resolve(process.env.STUDIO_CAPTURES || fileURLToPath(new URL("../data/captures", import.meta.url)));
const types = { ".html": "text/html; charset=utf-8", ".css": "text/css", ".js": "text/javascript",
  ".png": "image/png", ".svg": "image/svg+xml", ".json": "application/json", ".woff2": "font/woff2" };
const ids = new Set(scenes.map(scene => scene.id));
async function captureIndex() {
  let names;
  try { names = await readdir(captures); } catch (e) { if (e.code === "ENOENT") return {}; throw e; }
  const result = Object.create(null);
  for (const name of names) {
    const id = name.slice(0, -5);
    if (!name.endsWith(".json") || !ids.has(id)) continue;
    const record = JSON.parse(await readFile(resolve(captures, name), "utf8"));
    if (record.id !== id || !/^[a-f0-9]{64}\.png$/.test(record.image)) continue;
    result[id] = { ...record, image: "/actual/" + record.image };
  }
  return result;
}
createServer(async (req, res) => {
  if (!["GET", "HEAD"].includes(req.method)) { res.writeHead(405); res.end(); return; }
  try {
    let path = decodeURIComponent(new URL(req.url, "http://localhost").pathname);
    if (path === "/") path = "/studio/index.html";
    if (path === "/lab") path = "/index.html";
    const type = types[extname(path)];
    if (!type || path.includes("\\") || path.split("/").some(p => p.startsWith(".") || p === "tests" || p === "scripts"))
      throw Error("Unknown resource");
    let body;
    if (path === "/actual/index.json") body = JSON.stringify(await captureIndex());
    else if (/^\/actual\/[a-f0-9]{64}\.png$/.test(path)) body = await readFile(resolve(captures, path.slice(8)));
    else body = await readFile(resolve(root, "." + path));
    res.writeHead(200, { "Content-Type": type, "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
      "Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' blob:; connect-src 'self'; media-src 'none'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'" });
    res.end(req.method === "HEAD" ? undefined : body);
  } catch { res.writeHead(404); res.end("Not found"); }
}).on("error", error => { console.error(error.message); process.exitCode = 1; })
  .listen(Number(process.env.RADIO_SIM_PORT ?? 4176), "127.0.0.1", function () {
    console.log("NeoTalkie 手台设计工作台 http://127.0.0.1:" + this.address().port);
  });
