import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const root = resolve(process.argv[2] ?? ".");
const blueprintPath = resolve(root, "contracts/topic-registry/plutus.json");
const outDir = resolve(root, "contracts/topic-registry/build/cardano-cli");
const blueprint = JSON.parse(readFileSync(blueprintPath, "utf8"));

const scripts = new Map([
  ["topic_registry.topic_registry.mint", "topic-policy.plutus.json"],
  ["topic_registry.topic_registry.spend", "topic-state.plutus.json"],
]);

// Export the mint and spend handlers of one validator as cardano-cli script files.
const validators = [...scripts.keys()].map((title) => {
  const validator = blueprint.validators.find((candidate) => candidate.title === title);
  if (!validator) {
    throw new Error(`Aiken validator not found in blueprint: ${title}`);
  }
  return validator;
});
if (validators[0].compiledCode !== validators[1].compiledCode) {
  throw new Error("Topic mint and spend handlers must share one script hash");
}

mkdirSync(outDir, { recursive: true });

for (const [index, [title, filename]] of [...scripts].entries()) {
  const validator = validators[index];
  const outputPath = resolve(outDir, filename);
  mkdirSync(dirname(outputPath), { recursive: true });
  writeFileSync(outputPath, JSON.stringify({
    type: "PlutusScriptV3",
    description: title,
    cborHex: validator.compiledCode,
  }, null, 2));
  console.log(`${title} -> ${outputPath}`);
}
