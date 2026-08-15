#!/data/data/com.termux/files/usr/bin/bash

# minimal setup script to update / install packages and configure the env

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

place_staged_assets

skip_if_marked "base" "Base packages"

step 1 "Update package lists"

pkg update -y -o Dpkg::Use-Pty=0 || { log_error "pkg update failed"; exit 1; }

pkg upgrade -y -o Dpkg::Use-Pty=0 -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" || \
  log_warn "pkg upgrade had issues, continuing anyway"

step 2 "Install base packages"

# - python: proot-distro or clear_execstack.py (unused?)
# - tur-repo: code-server's repo (IDEA pkg install in start-code-server.sh)

pkg install -y -o Dpkg::Use-Pty=0 -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" which git tur-repo python wget openssl-tool || \
  { log_error "pkg install failed"; exit 1; }

# NOTE vscode expects the /certs folder to exist
mkdir -p $TERMUX_PREFIX/etc/tls/certs
ln -s $TERMUX_PREFIX/etc/tls/cert.pem $TERMUX_PREFIX/etc/tls/certs/ca-certificates.crt

step 3 "Writing shared environment file"

# non-login interactive shells read .bashrc (not .profile)
# login, .bash_profile/.bash_login/.profile (not .bashrc)
# Use a shared env instead; .bash_profile uses .bashrc

ENV_FILE="$HOME/.local/env"

mkdir -p "$HOME/.local/bin" "$HOME/.local/lib/node_modules"

cat > "$ENV_FILE" <<EOF
export LD_PRELOAD=$TERMUX_PREFIX/lib/libtermux-exec-direct-ld-preload.so

# The app directory (i.e /data/data/com.example.vscodetermux/)
# NOTE There's likely a better way to get this path. 
# IDEA make a symlink somewhere. i.e ~/.local/app_data?

export APP_DIR=$(dirname "$(dirname $LD_LIBRARY_PATH)")

# Set npm -g path to match code-server (~/.local/share/code-server)
export NPM_CONFIG_PREFIX=~/.local
export NODE_PATH=~/.local/lib/node_modules
export PATH=~/.local/bin:\$PATH

EOF
  # install-android-devtools.sh appends exports later
  # (JAVA_HOME, ANDROID_HOME, etc.) may not exist yet

touch "$HOME/.bashrc" "$HOME/.bash_profile"
grep -qF ". $ENV_FILE" "$HOME/.bashrc" || echo ". $ENV_FILE" >> "$HOME/.bashrc"
grep -qF ". $ENV_FILE" "$HOME/.bash_profile" || echo ". $ENV_FILE" >> "$HOME/.bash_profile"
grep -qF '. "$HOME/.bashrc"' "$HOME/.bash_profile" || echo '[ -f "$HOME/.bashrc" ] && . "$HOME/.bashrc"' >> "$HOME/.bash_profile"
source "$ENV_FILE"

# matching termux storage/shared convention
mkdir -p "$HOME/storage"
ln -sf /sdcard "$HOME/storage/shared" 2>/dev/null || true

step 4 "Linking devcontainer onto PATH"

# IDEA: Relocate examples to usr/opt/ in .kt files; VscodeTermuxApp.kt... or
# mv $HOME/examples $TERMUX_PREFIX/opt

# link `devcontainer up` bind-mounted at $TERMUX_PREFIX/libexec/vscodetermux/ 
ln -sf "$TERMUX_PREFIX/libexec/vscodetermux/devcontainer" "$TERMUX_PREFIX/bin/devcontainer" 2>/dev/null || true

step 5 "Writing base marker"

mark_done "base"

step 6 "Base install complete"
log_ok "Cleaning up temporary install files"

pkg clean || true
pkg autoclean
