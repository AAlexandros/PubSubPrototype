#!/usr/bin/env bash
set -euo pipefail

DEVNET_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJECT_ROOT="$(cd "$DEVNET_DIR/../../.." && pwd)"
RUNTIME_DIR="$DEVNET_DIR/runtime"
KEYS_DIR="$DEVNET_DIR/keys"
STATE_DIR="$DEVNET_DIR/state"
VERSIONS_FILE="$DEVNET_DIR/versions.env"
COMPOSE_FILE="$DEVNET_DIR/compose.yaml"

if [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]; then
  export MSYS_NO_PATHCONV=1
  export MSYS2_ARG_CONV_EXCL="*"
fi

docker_host_path() {
  if grep -qi microsoft /proc/version 2>/dev/null && command -v wslpath >/dev/null 2>&1; then
    wslpath -w "$1"
  elif command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    printf '%s\n' "$1"
  fi
}

DOCKER_DEVNET_DIR="$(docker_host_path "$DEVNET_DIR")"
DOCKER_VERSIONS_FILE="$(docker_host_path "$VERSIONS_FILE")"
DOCKER_COMPOSE_FILE="$(docker_host_path "$COMPOSE_FILE")"
DOCKER_PHASE_COMPOSE_FILE="$(docker_host_path "$PROJECT_ROOT/ops/infra/phase-0.1/compose.yaml")"

if [ -f "$VERSIONS_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$VERSIONS_FILE"
  set +a
fi

NETWORK_MAGIC="${CARDANO_NETWORK_MAGIC:-42}"
TESTNET_DIR="$RUNTIME_DIR/testnet"
SOCKET_PATH="${CARDANO_NODE_SOCKET_PATH:-$TESTNET_DIR/socket/node1/sock}"
NETWORK_ENV="$RUNTIME_DIR/network.env"

docker_compose() {
  docker compose --env-file "$DOCKER_VERSIONS_FILE" -f "$DOCKER_COMPOSE_FILE" "$@"
}

phase_compose() {
  docker compose --env-file "$DOCKER_VERSIONS_FILE" -f "$DOCKER_PHASE_COMPOSE_FILE" "$@"
}

have_command() {
  command -v "$1" >/dev/null 2>&1
}

cardano_cli() {
  if have_command cardano-cli; then
    CARDANO_NODE_SOCKET_PATH="$SOCKET_PATH" cardano-cli "$@"
  else
    local translated=()
    local arg
    for arg in "$@"; do
      translated+=("${arg/#$DEVNET_DIR/\/devnet}")
    done
    docker_compose exec -T -e CARDANO_NODE_SOCKET_PATH=/devnet/runtime/testnet/socket/node1/sock cardano-node cardano-cli "${translated[@]}"
  fi
}

cardano_cli_offline() {
  if have_command cardano-cli; then
    cardano-cli "$@"
  elif have_command docker; then
    local translated=()
    local arg
    for arg in "$@"; do
      translated+=("${arg/#$DEVNET_DIR/\/devnet}")
    done
    docker run --rm --entrypoint cardano-cli \
      -v "$DOCKER_DEVNET_DIR:/devnet" \
      -w /devnet \
      "${CARDANO_NODE_IMAGE:-ghcr.io/intersectmbo/cardano-node:11.0.1}" "${translated[@]}"
  else
    echo "cardano-cli is not on PATH and Docker is not available." >&2
    exit 1
  fi
}

require_cardano_testnet_runtime() {
  if have_command cardano-testnet && have_command cardano-node && have_command cardano-cli; then
    return 0
  fi
  if have_command docker; then
    return 0
  fi
  echo "cardano-testnet/cardano-node/cardano-cli are not on PATH and Docker is not available." >&2
  exit 1
}

identity_address_var() {
  case "$1" in
    registry-deployer) echo "REGISTRY_DEPLOYER_ADDRESS" ;;
    node-1) echo "NODE_1_CARDANO_ADDRESS" ;;
    node-2) echo "NODE_2_CARDANO_ADDRESS" ;;
    node-3) echo "NODE_3_CARDANO_ADDRESS" ;;
    *) echo "Unsupported identity: $1" >&2; exit 1 ;;
  esac
}

write_network_env() {
  mkdir -p "$RUNTIME_DIR"
  {
    echo "CARDANO_NODE_SOCKET_PATH=$SOCKET_PATH"
    echo "CARDANO_NETWORK_MAGIC=$NETWORK_MAGIC"
    for name in registry-deployer node-1 node-2 node-3; do
      var="$(identity_address_var "$name")"
      addr_file="$KEYS_DIR/$name/payment.addr"
      if [ -f "$addr_file" ]; then
        echo "$var=$(cat "$addr_file")"
      fi
    done
  } > "$NETWORK_ENV"
}

query_tip_json() {
  cardano_cli query tip --testnet-magic "$NETWORK_MAGIC"
}

extract_json_number() {
  sed -n "s/.*\"$1\"[[:space:]]*:[[:space:]]*\\([0-9][0-9]*\\).*/\\1/p"
}

identity_balance_lovelace() {
  local name="$1"
  local addr
  addr="$(cat "$KEYS_DIR/$name/payment.addr")"
  address_balance_lovelace "$addr"
}

identity_utxo_count() {
  local name="$1"
  local addr
  addr="$(cat "$KEYS_DIR/$name/payment.addr")"
  address_utxos "$addr" | wc -l | tr -d ' '
}

address_balance_lovelace() {
  local addr="$1"
  cardano_cli query utxo --address "$addr" --testnet-magic "$NETWORK_MAGIC" \
    | awk '
        /"lovelace"/ {
          line = $0
          sub(/.*"lovelace"[[:space:]]*:[[:space:]]*/, "", line)
          sub(/[^0-9].*/, "", line)
          total += line
          seen_json = 1
        }
        /lovelace/ && !/"lovelace"/ {
          total += $3
        }
        END { print total + 0 }
      '
}

address_utxos() {
  local addr="$1"
  cardano_cli query utxo --address "$addr" --testnet-magic "$NETWORK_MAGIC" \
    | awk '
        /^[[:space:]]*"[0-9a-f]+#[0-9]+"/ {
          line = $0
          sub(/^[[:space:]]*"/, "", line)
          sub(/".*/, "", line)
          print line
        }
        /lovelace/ && !/"lovelace"/ {
          print $1 "#" $2
        }
      '
}

build_payment_address() {
  local vkey_file="$1"
  local out_file="$2"
  cardano_cli_offline address build \
    --payment-verification-key-file "$vkey_file" \
    --testnet-magic "$NETWORK_MAGIC" \
    --out-file "$out_file"
}

build_genesis_initial_address() {
  local vkey_file="$1"
  cardano_cli_offline genesis initial-addr \
    --verification-key-file "$vkey_file" \
    --testnet-magic "$NETWORK_MAGIC"
}
