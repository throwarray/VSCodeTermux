#!/data/data/com.termux/files/usr/bin/bash
set -e

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

CODE_SERVER_PORT="${CODE_SERVER_PORT:-8080}"
CODE_SERVER_WORKSPACE="${CODE_SERVER_WORKSPACE:-$HOME/workspace}"

NPM_CODE_SERVER_DIR="$TERMUX_PREFIX/lib/node_modules/code-server"
NPM_ARGON2_MODULE="$NPM_CODE_SERVER_DIR/node_modules/argon2/argon2.js"
NPM_ARGON2_BINDING="$NPM_CODE_SERVER_DIR/node_modules/argon2/lib/binding/napi-v3/argon2.node"

stub_out_argon2_if_needed() {
  if [ -f "$NPM_CODE_SERVER_DIR/out/node/cli.js" ] && [ ! -f "$NPM_ARGON2_BINDING" ]; then
    log_debug "argon2's native binding didn't build — stubbing it out (auth: none never actually calls it, this only stops the unconditional require() from crashing startup)…"

    mkdir -p "$(dirname "$NPM_ARGON2_MODULE")"
    cat > "$NPM_ARGON2_MODULE" <<'STUB'
function unavailable() {
  throw new Error(
    "argon2 native binding not available (stubbed — auth: none doesn't need it)"
  );
}

module.exports = { 
  hash: unavailable, verify: unavailable, 
  argon2i: 0, argon2d: 1, argon2id: 2 
};

STUB
  fi
}

# These files are only written if missing to respect user changes.
# IDEA: marker deletion should update (settings, config, platform-shim)

SETTINGS_DIR="$HOME/.local/share/code-server/User"
CONF_DIR=$HOME/.local/share/code-server
SHIM_DIR=$HOME/.local/share/code-server

# The marker guards the pkg install and post-install step.
INSTALLED_MARKER="codeserver"

if is_marked "$INSTALLED_MARKER"; then
  code_server_bin="$(command -v code-server || true)"
else
  code_server_bin=""

  # IDEA Purge the webview
  # rm -rf /data/data/com.example.vscodetermux/app_webview/*

fi

if [ -z "$code_server_bin" ]; then
  log_info "Installing code-server from TUR…"

  pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" tur-repo
  pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" code-server || log_warn "TUR's code-server didn't come through cleanly — falling back to npm."
  code_server_bin="$(command -v code-server || true)"

  if [ -n "$code_server_bin" ] && ! "$code_server_bin" --version >/dev/null 2>&1; then
    code_server_bin=""
  fi
fi

if [ -z "$code_server_bin" ]; then
  stub_out_argon2_if_needed  
  code_server_bin="$(command -v code-server || true)"
  
  if [ -n "$code_server_bin" ] && ! "$code_server_bin" --version >/dev/null 2>&1; then
    code_server_bin=""
  fi
fi

# TODO: Deprecate? Might've made sense inside a custom proot.
if [ -z "$code_server_bin" ]; then
  log_info "Installing code-server via npm (TUR package unavailable)…"
  pkg install -y -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" nodejs
  npm install -g code-server || true
  stub_out_argon2_if_needed

  code_server_bin="$(command -v code-server || true)"
  
  if [ -z "$code_server_bin" ] || ! "$code_server_bin" --version >/dev/null 2>&1; then
    log_error "code-server not runnable after TUR and npm both failed."
  fi
fi

# If there's nodejs binaries under opt/nodejs-<major>/bin 
# without the bin/ symlinks (i.e postinst not completing)

if [ ! -x "$TERMUX_PREFIX/bin/node" ]; then
  NODE_OPT_DIR="$(ls -d "$TERMUX_PREFIX"/opt/nodejs-*/bin 2>/dev/null | head -n1)"

  if [ -n "$NODE_OPT_DIR" ]; then
    log_debug "node/npm/npx found under $NODE_OPT_DIR but not linked into bin/ — fixing"
    
    for bin in node npm npx; do
      [ -x "$NODE_OPT_DIR/$bin" ] && ln -sf "$NODE_OPT_DIR/$bin" "$TERMUX_PREFIX/bin/$bin"
    done
  fi
fi

mkdir -p "$HOME/workspace" "$HOME/storage" "$HOME/.local/share/code-server"


# Localize the launch config. "$HOME/.local/share/code-server"
# instead of default location "$HOME/.config/code-server/config.yaml"

TLS_DIR="$CONF_DIR/tls"

NEEDS_CONFIG_WRITE=0
if [ ! -f "$CONF_DIR/config.yaml" ]; then
  NEEDS_CONFIG_WRITE=1
elif grep -qE '^auth: none|^cert: (true|false)$' "$CONF_DIR/config.yaml"; then
  # One-time migration: installs from before auth was enabled, or from
  # earlier iterations that used cert: true/false instead of a real,
  # app-trusted cert (see below for why).
  log_info "Migrating config.yaml to enable auth + TLS…"
  NEEDS_CONFIG_WRITE=1
fi

if [ "$NEEDS_CONFIG_WRITE" = "1" ]; then
  mkdir -p "$CONF_DIR"

  # Random password, generated once and persisted below — change it any
  # time by editing this file directly, or swap in your own hashed-password.
  # Deliberately NOT using hashed-password by default: it requires argon2 to
  # verify, and this environment's argon2 native binding doesn't build (see
  # stub_out_argon2_if_needed above). Plain `password:` doesn't need argon2 —
  # code-server just does a constant-time string compare against it.
  GENERATED_PASSWORD="$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"

  # A real cert, not cert: true/false. Password auth over plain HTTP would
  # send the password in cleartext to anything else on the LAN if this ever
  # binds beyond loopback — auth would be security theater. The cert itself
  # (assets/tls/, staged here by place_staged_assets) isn't generated
  # on-device; it's fixed and shipped with the app so the same cert can be
  # declared as a trust anchor in res/xml/network_security_config.xml. That
  # makes the WebView trust it at the network-stack level rather than via a
  # runtime SSL-error bypass, which covers WebSocket connections (terminal,
  # extension host) too, not just page loads.
  cat > "$CONF_DIR/config.yaml" <<EOF
bind-addr: 127.0.0.1:$CODE_SERVER_PORT
auth: password
password: $GENERATED_PASSWORD
cert: $TLS_DIR/cert.pem
cert-key: $TLS_DIR/key.pem
disable-telemetry: true
disable-update-check: true
disable-workspace-trust: true

EOF

  log_ok "code-server auth configured — password saved in $CONF_DIR/config.yaml (also viewable from the app's Server Info screen)."
fi

# Tuning for low memory.
# "java.jdt.ls.vmargs": The language server extension spawns a JVM process
# that doesn't inherit the envs memory tuning, and otherwise defaults to a 2GB heap.

if [ ! -f "$SETTINGS_DIR/settings.json" ]; then
  mkdir -p "$SETTINGS_DIR"  
  cat > "$SETTINGS_DIR/settings.json" <<'EOF'
{
  "java.jdt.ls.vmargs": "-Xmx512m -XX:+UseSerialGC",
  "workbench.activityBar.compact": true,
  "workbench.editor.empty.hint": "hidden",
  "redhat.telemetry.enabled": false,
  "java.server.launchMode": "LightWeight",
  "java.configuration.workspaceCacheLimit": 10,
  "java.typeHierarchy.lazyLoad": true,
  "workbench.reduceMotion": "on",
  "workbench.reduceTransparency": "on",
  "chat.tools.compressOutput.enabled": true,
  "chat.progressBorder.enabled": false,
  "java.jdt.ls.androidSupport.enabled": "on",
  "workbench.startupEditor": "none",
  "java.autobuild.enabled": false,
  "editor.fontSize": 10,
  "files.autoSave": "off",
  "editor.accessibilityPageSize": 250,
  "editor.maxTokenizationLineLength": 2500,
  "telemetry.telemetryLevel": "off",
  "js/ts.tsserver.maxMemory": 512,
  "terminal.integrated.scrollback": 350,
  "terminal.integrated.fontSize": 8,
  "terminal.integrated.hideOnStartup": "whenEmpty",
  "http.systemCertificatesNode": true,
}
EOF
fi

# Shim, scoped code-server's own process only
# spoofs process.platform to 'linux' for extension compatibility

if [ ! -f "$SHIM_DIR/platform-shim.js" ]; then
  mkdir -p "$SHIM_DIR"
  cat > "$SHIM_DIR/platform-shim.js" <<'EOF'
Object.defineProperty(process, 'platform', { get() { return 'linux'; } });

EOF
fi

if [ -z "$code_server_bin" ]; then
  log_error "code-server binary not found."
  exit 1
fi


if ! is_marked "$INSTALLED_MARKER"; then
  mark_done "$INSTALLED_MARKER"

  # post install step
  # (i.e restore config / add vsix extensions...)

  pkg clean || true
  pkg autoclean || true
fi

# Launch the vscode server process
# NOTE --max-old-space-size=512 wasn't large enough trying 608
export NODE_OPTIONS="${NODE_OPTIONS} --max-old-space-size=608 --require $SHIM_DIR/platform-shim.js"

exec "$code_server_bin" --config $CONF_DIR/config.yaml --disable-telemetry "$CODE_SERVER_WORKSPACE"