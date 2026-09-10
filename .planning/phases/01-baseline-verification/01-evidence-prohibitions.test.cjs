const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const subject = process.env.GSD_PROHIB_SUBJECT
  ?? path.join(__dirname, "01-BASELINE.md");
const document = fs.readFileSync(subject, "utf8");

test("current PASS rows exclude incompatible result semantics", () => {
  const rows = document
    .split("\n")
    .filter((line) => /^\| (?:BASE|ADMIN|PUBLIC|MODEL|INDEX|BUILD|INGEST|CONNECTOR|SEARCH|MCP)-\d{2} \|/.test(line));

  for (const row of rows) {
    const cells = row.split("|").map((cell) => cell.trim());
    if (cells[5] === "PASS") {
      assert.doesNotMatch(cells[6], /\b(?:historical|STATIC_MATCH|NOT_RUN|ENVIRONMENT_BLOCKED)\b/i);
    }
  }
});

test("all confirmed active gaps keep their assigned routes", () => {
  const routes = new Map([
    ["D-06", "Phase 4, Phase 5"],
    ["D-07", "Phase 4"],
    ["D-08", "Phase 5"],
    ["D-09", "Phase 5"],
    ["D-13", "Phase 3"],
    ["D-14", "Phase 2"],
  ]);

  for (const [decision, phase] of routes) {
    assert.match(document, new RegExp(`^\\| ${decision} \\|.*\\| ${phase.replace(",", "\\,")} \\|$`, "m"));
  }
});
