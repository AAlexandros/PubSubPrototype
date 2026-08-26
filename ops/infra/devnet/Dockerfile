FROM nixos/nix:2.24.9

ARG CARDANO_TESTNET_VERSION=11.0.1

RUN nix --extra-experimental-features "nix-command flakes" \
    profile install --impure --accept-flake-config \
    "github:IntersectMBO/cardano-node/${CARDANO_TESTNET_VERSION}#cardano-node" \
    "github:IntersectMBO/cardano-node/${CARDANO_TESTNET_VERSION}#cardano-cli" \
    "github:IntersectMBO/cardano-node/${CARDANO_TESTNET_VERSION}#cardano-testnet"

ENV PATH="/root/.nix-profile/bin:${PATH}"
WORKDIR /devnet

COPY container/start-testnet.sh /usr/local/bin/start-testnet.sh
RUN chmod +x /usr/local/bin/start-testnet.sh

CMD ["/usr/local/bin/start-testnet.sh"]
