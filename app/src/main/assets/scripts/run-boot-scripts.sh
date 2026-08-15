#!/data/data/com.termux/files/usr/bin/bash

# Termux:Boot interop
# Runs everything in $HOME/.termux/boot/ at boot —

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

mkdir -p "$HOME/.termux/boot"
for f in "$HOME/.termux/boot"/*.sh; do
  [ -f "$f" ] || continue

  log_info "Running boot script: $f"

  # own subprocess, each script independent;
  # exec'ing or crashing shouldn't take out the loop.
  bash "$f" || log_warn "$f exited non-zero — continuing anyway."
done
