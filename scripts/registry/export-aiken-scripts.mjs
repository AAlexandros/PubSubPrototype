import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const root = resolve(process.argv[2] ?? ".");
const blueprintPath = resolve(root, "contracts/topic-registry/plutus.json");
const outDir = resolve(root, "contracts/topic-registry/build/cardano-cli");
const blueprint = JSON.parse(readFileSync(blueprintPath, "utf8"));

const scripts = new Map([
  ["topic_registry.topic_policy.mint", "topic-policy.plutus.json"],
  ["topic_registry.topic_state.spend", "topic-state.plutus.json"],
]);

// extracts the two compiled validators topic_registry.topic_policy.mint and topic_registry.topic_state.spend
// and writes each one into a separate JSON file in a format the cardano-cli can use
mkdirSync(outDir, { recursive: true });

for (const [title, filename] of scripts) {
  const validator = blueprint.validators.find((candidate) => candidate.title === title);
  if (!validator) {
    throw new Error(`Aiken validator not found in blueprint: ${title}`);
  }
  const outputPath = resolve(outDir, filename);
  mkdirSync(dirname(outputPath), { recursive: true });
  writeFileSync(outputPath, JSON.stringify({
    type: "PlutusScriptV3",
    description: title,
    cborHex: validator.compiledCode,
  }, null, 2));
  console.log(`${title} -> ${outputPath}`);
}
