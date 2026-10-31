FROM nixos/nix:2.24.9 AS cardano-cli
ARG CARDANO_TESTNET_VERSION=11.0.1
RUN nix --extra-experimental-features "nix-command flakes" \
    profile install --impure --accept-flake-config \
    "github:IntersectMBO/cardano-node/${CARDANO_TESTNET_VERSION}#cardano-cli"

FROM eclipse-temurin:21-jre
COPY --from=cardano-cli /nix/store /nix/store
COPY --from=cardano-cli /root/.nix-profile/bin/cardano-cli /usr/local/bin/cardano-cli

WORKDIR /app
COPY apps/pubsub-node/build/install/pubsub-node/ /app/

ENTRYPOINT ["/app/bin/pubsub-node"]
