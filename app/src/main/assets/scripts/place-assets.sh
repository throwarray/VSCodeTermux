#!/data/data/com.termux/files/usr/bin/bash

# Places asset trees staged by AssetStager.kt (see VscodeTermuxApp.kt) into
# their final locations under $HOME. This is the one place that should know
# "workspace assets go to $HOME/workspace", "examples go to $HOME/examples",
# etc — keep destination decisions here, not scattered across Kotlin and
# other bootstrap scripts.

set -u

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

STAGED="$LIBEXEC/assets"

place() {
  local name="$1" dest="$2"
  local src="$STAGED/$name"

  [ -d "$src" ] || return 0

  # Idempotent: once dest has content, leave it alone (e.g. user has since
  # edited/deleted files in there — don't stomp on that every launch).
  if [ -d "$dest" ] && [ -n "$(ls -A "$dest" 2>/dev/null)" ]; then
    return 0
  fi

  mkdir -p "$dest"
  cp -a "$src/." "$dest/"
}

place "workspace" "$HOME/workspace"
place "examples"  "$HOME/examples"
place "boot"      "$HOME/.termux/boot"
