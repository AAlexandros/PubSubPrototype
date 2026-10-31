#!/usr/bin/env bash
set -euo pipefail

AIKEN_VERSION="${AIKEN_VERSION:-1.1.23}"
AIKEN_TOOLS_DIR="$ROOT_DIR/.tools/aiken-$AIKEN_VERSION"

aiken_target() {
  local os arch
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  arch="$(uname -m)"

  case "$os:$arch" in
    linux:aarch64|linux:arm64) printf '%s\n' "aarch64-unknown-linux-musl" ;;
    linux:x86_64|linux:amd64) printf '%s\n' "x86_64-unknown-linux-musl" ;;
    *) return 1 ;;
  esac
}

download_aiken() {
  local target archive url
  target="$(aiken_target)" || return 1
  archive="$AIKEN_TOOLS_DIR/aiken.tar.gz"
  url="https://github.com/aiken-lang/aiken/releases/download/v$AIKEN_VERSION/aiken-$target.tar.gz"

  mkdir -p "$AIKEN_TOOLS_DIR"
  if [ ! -x "$AIKEN_TOOLS_DIR/aiken" ]; then
    echo "Downloading Aiken v$AIKEN_VERSION for $target"
    curl --proto '=https' --tlsv1.2 -fsSL "$url" -o "$archive"
    tar -xzf "$archive" -C "$AIKEN_TOOLS_DIR"
    cp "$AIKEN_TOOLS_DIR/aiken-$target/aiken" "$AIKEN_TOOLS_DIR/aiken"
    chmod +x "$AIKEN_TOOLS_DIR/aiken"
  fi
}

run_aiken_docker() {
  local project="$1"
  shift
  local host_root
  host_root="$(pubsub_host_path "$ROOT_DIR")"

  # The mounted repository supplies the contracts and stores the pinned Aiken binary.
  MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' docker run --rm \
    --mount "type=bind,source=$host_root,target=/workspace" \
    --workdir "/workspace/contracts/$project" \
    --env "AIKEN_VERSION=$AIKEN_VERSION" \
    "${AIKEN_DOCKER_IMAGE:-nixos/nix:2.24.9}" \
    bash /workspace/scripts/registry/build-aiken-docker.sh "$@"
}
