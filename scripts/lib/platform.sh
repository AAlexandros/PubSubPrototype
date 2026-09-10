#!/usr/bin/env bash

# Shared host/platform behavior for repository shell entrypoints. Functions are
# prefixed so sourcing this file never shadows a script's domain-specific names.

pubsub_is_wsl() {
  grep -qi microsoft /proc/version 2>/dev/null
}

pubsub_is_msys() {
  [[ "${OSTYPE:-}" == msys* || "${OSTYPE:-}" == cygwin* ]]
}

pubsub_uses_windows_tools() {
  pubsub_is_wsl || pubsub_is_msys
}

pubsub_enable_msys_path_passthrough() {
  if pubsub_is_msys; then
    export MSYS_NO_PATHCONV=1
    export MSYS2_ARG_CONV_EXCL="*"
  fi
}

pubsub_host_path() {
  local path="$1"
  if pubsub_is_wsl && command -v wslpath >/dev/null 2>&1; then
    wslpath -w "$path"
  elif command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$path"
  else
    printf '%s\n' "$path"
  fi
}

pubsub_node_is_windows() {
  [[ "$(node -p 'process.platform' 2>/dev/null)" == "win32" ]]
}

pubsub_node_path() {
  if pubsub_node_is_windows; then
    pubsub_host_path "$1"
  else
    printf '%s\n' "$1"
  fi
}

pubsub_run_gradle() {
  local root="$1"
  shift
  if pubsub_uses_windows_tools; then
    (cd "$root" && cmd.exe /c gradlew.bat -g .gradle-user-home "$@")
  else
    "$root/gradlew" --gradle-user-home "$root/.gradle-user-home" "$@"
  fi
}

pubsub_run_node_file() {
  local script
  script="$(pubsub_node_path "$1")"
  shift
  node "$script" "$@"
}
