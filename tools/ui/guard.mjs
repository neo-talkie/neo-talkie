import { sourceHashes } from "./source-hashes.mjs";
// Review gate for UI adapter logic that cannot be expressed as contract data.
import { readFile, writeFile, readdir } from "node:fs/promises";
import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
const root = new URL("../../", import.meta.url),
  rootPath = fileURLToPath(root);
const args = process.argv.slice(2),
  accept = args.includes("--accept");
const reason = args.includes("--reason")
  ? args[args.indexOf("--reason") + 1]
  : "";
const hashes = await sourceHashes(),
  files = Object.keys(hashes);
const path = new URL("config/radio-ui/review.json", root);
if (accept) {
  if (process.env.CI) throw Error("CI cannot update the UI review record");
  if (!reason || reason.startsWith("--"))
    throw Error(
      'Use --accept --reason "what changed and how both adapters were reviewed"',
    );
  for (const cmd of [
    ["tools/ui/generate.mjs", "--check"],
    ["tools/ui/parity.mjs"],
    ["tools/simulator/scripts/catalog.mjs", "--check"],
  ])
    execFileSync(process.execPath, cmd, { cwd: rootPath, stdio: "inherit" });
  const report = JSON.parse(
    await readFile(
      new URL("data/ui-regression/latest/visual-diff.json", root),
      "utf8",
    ),
  );
  // The visual runner records its exact source inputs; stale screenshots cannot be accepted.
  if (JSON.stringify(report.sourceHashes) !== JSON.stringify(hashes))
    throw Error(
      "Run make ui-visual after the last UI edit before accepting review",
    );
  await writeFile(
    path,
    JSON.stringify(
      { schema: "neotalkie-radio-ui-review/v1", reason, hashes },
      null,
      2,
    ) + "\n",
  );
  console.log(
    "UI review record updated. Commit it alongside the implementation and contract.",
  );
} else {
  const reviewed = JSON.parse(
    await readFile(path, "utf8").catch(() => '{"hashes":{}}'),
  );
  const changed = files.filter((f) => reviewed.hashes?.[f] !== hashes[f]);
  for (const f of Object.keys(reviewed.hashes || {}))
    if (!hashes[f]) changed.push(f + " (removed)");
  if (changed.length)
    throw Error(
      "UI adapters changed since the last reviewed screenshots:\n" +
        changed.join("\n") +
        '\nRun make ui-visual, inspect its report, then make ui-accept REASON= "...".',
    );
  console.log(`UI adapter review gate: ${files.length} source inputs match`);
}
