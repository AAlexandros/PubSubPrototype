# Topic Registry contract

`validators/topic_registry.ak` contains one Aiken validator with `mint` and `spend` handlers. Both handlers share a script hash, which is both the topic policy ID and the registry script's payment credential.

- Minting consumes a creation seed, mints exactly one uniquely named topic token, and places it at the registry script with a valid inline topic datum.
- Spending keeps the token at the same script address and accepts only authorized topic transitions with a valid replacement datum. Deleted topics remain as inactive tombstones.
- The datum rules include unique actors within each role list, Cardano payment-key hash lengths for owners and admins, event-key ID lengths for publishers, UTF-8 topic names, and the Java-supported replication and retention ranges.

`CardanoTopicRegistry` queries the live script UTxOs using the deployed address and policy ID. It ignores outputs without that policy token and rejects inconsistent registry-token outputs before publishing a topic snapshot. No shared UTxO cache is used for runtime decisions.

`scripts/registry/build.sh` compiles the contract, exports cardano-cli script files, and runs the Java registry tests. Because the mint and spend handlers share one script, changing this contract changes both the validator address and topic policy ID; a fresh deployment is required.
