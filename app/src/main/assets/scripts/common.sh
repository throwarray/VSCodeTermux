#!/data/data/com.termux/files/usr/bin/bash

# Shared setup sourced by every bootstrap script. One copy of the paths/env
# vscodetermux's proot environment needs, instead of each script redefining
# it slightly differently.

export TERMUX_PREFIX="/data/data/com.termux/files/usr"
export LIBEXEC="$TERMUX_PREFIX/libexec/vscodetermux"
export LD_PRELOAD="$TERMUX_PREFIX/lib/libtermux-exec-direct-ld-preload.so"
export DEBIAN_FRONTEND=noninteractive

# Using PTY (TerminalSession) instead of a ProcessBuilder pipe, apt/dpkg can
# detect a TTY and pipe long output (upgrade lists, changelogs) through a
# pager (less). Forcing PAGER=cat means anything that tries to page prints
# straight through instead of hanging, waiting for input.
export PAGER=cat
export GIT_PAGER=cat

# --- Logging -----------------------------------------------------------
# LOG_LEVEL controls verbosity: error < warn < info < debug (default: info)

: "${LOG_LEVEL:=info}"

_log_level_num() {
  case "$1" in
    error) echo 0 ;;
    warn)  echo 1 ;;
    info)  echo 2 ;;
    debug) echo 3 ;;
    *)     echo 2 ;;
  esac
}

_log_enabled() {
  [ "$(_log_level_num "$1")" -le "$(_log_level_num "$LOG_LEVEL")" ]
}

# Color codes, skipped when stdout isn't a terminal (e.g. piped to a file).
if [ -t 1 ]; then
  _C_RED=$'\033[31m'; _C_GREEN=$'\033[32m'; _C_YELLOW=$'\033[33m'
  _C_CYAN=$'\033[36m'; _C_DIM=$'\033[2m'; _C_RESET=$'\033[0m'
else
  _C_RED=""; _C_GREEN=""; _C_YELLOW=""; _C_CYAN=""; _C_DIM=""; _C_RESET=""
fi

log_error() { ! _log_enabled error || echo "${_C_RED}ERROR: $*${_C_RESET}" >&2; }
log_warn()  { ! _log_enabled warn  || echo "${_C_YELLOW}WARNING: $*${_C_RESET}" >&2; }
log_info()  { ! _log_enabled info  || echo "$*"; }
log_ok()    { ! _log_enabled info  || echo "${_C_GREEN}$*${_C_RESET}"; }
log_debug() { ! _log_enabled debug || echo "${_C_DIM}$*${_C_RESET}"; }

# Numbered step logging, e.g: step 3 "Writing shared environment file"
step() { log_info "${_C_CYAN}Step $1:${_C_RESET} $2"; }

# --- Marker helpers (guard install steps that should only run once) ----
# Pass a short name ("base", "toolchain", ...), not a path — this owns
# where markers actually live so call sites don't need to know or care.

_marker_path() { echo "$TERMUX_PREFIX/etc/vscodetermux-$1-installed"; }

# True if $1 has already been marked done.
is_marked() { [ -f "$(_marker_path "$1")" ]; }

# Marks $1 as done for is_marked/skip_if_marked to pick up next run.
mark_done() {
  mkdir -p "$TERMUX_PREFIX/etc"
  touch "$(_marker_path "$1")"
}

# Exits successfully with a message if $1 is already marked done. Put this
# at the top of a script that should be a no-op after its first success.
skip_if_marked() {
  local name="$1" label="$2"
  if is_marked "$name"; then
    log_ok "$label already installed — skipping."
    exit 0
  fi
}

# --- Asset placement -----------------------------------------------------
# Places asset trees staged by AssetStager.kt (see VscodeTermuxApp.kt) into
# their final locations under $HOME. This is the one place that should know
# "workspace assets go to $HOME/workspace", "examples go to $HOME/examples",
# etc — keep destination decisions here, not scattered across Kotlin and
# other bootstrap scripts. Idempotent: skips any dest that already has
# content (e.g. the user has since edited/deleted files in there).

place_staged_assets() {
  local staged="$LIBEXEC/assets"

  _place() {
    local name="$1" dest="$2" src="$staged/$1"
    [ -d "$src" ] || return 0
    if [ -d "$dest" ] && [ -n "$(ls -A "$dest" 2>/dev/null)" ]; then
      return 0
    fi
    mkdir -p "$dest"
    cp -a "$src/." "$dest/"
  }

  _place "workspace" "$HOME/workspace"
  _place "examples"  "$HOME/examples"
  _place "boot"      "$HOME/.termux/boot"

  # tls is unconditional (not _place's skip-if-populated) — it's shipped
  # config generated fresh per build machine (see build.sh), not user
  # content. If it lagged behind on an in-place app update, the WebView
  # (trusting whatever's in the new APK's res/raw) and code-server
  # (serving whatever's staged here) could end up trusting different
  # certs and silently fail to connect.
  if [ -d "$staged/tls" ]; then
    mkdir -p "$HOME/.local/share/code-server/tls"
    cp -a "$staged/tls/." "$HOME/.local/share/code-server/tls/"
  fi
}
