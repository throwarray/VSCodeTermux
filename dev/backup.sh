#!/data/data/com.termux/files/usr/bin/bash

# Copy various files to later restore or include into the build process

TERMUX_PREFIX=/data/data/com.termux/files/usr

INSTALLED_MARKER=$TERMUX_PREFIX/etc/vscodetermux-base-installed

VSC_DIR=$HOME/.local/share/code-server

DIR="$(cd "$(dirname "$0")" && pwd)"

if [ ! -f "$INSTALLED_MARKER" ]; then
  echo "Unknown platform or the bootstrap not finished."
  exit 1
fi

cd $DIR

mkdir -p ./backup

cd ./backup

echo "Copying scripts and examples to $(pwd)."

mkdir -p ./scripts ./examples ./.android ./code-server ./code-server/extensions ./code-server/User

cp -r $HOME/examples ./
cp -r $TERMUX_PREFIX/libexec/vscodetermux/* ./scripts

echo "Copying VSCode settings and extensions."

cp $VSC_DIR/User/settings.json ./code-server/User
cp -r $VSC_DIR/extensions ./code-server

# NOTE: the default path (legacy) and the localized (custom) path
cp $HOME/.config/code-server/config.yaml ./code-server 2>/dev/null || true 
cp $VSC_DIR/config.yaml ./code-server 2>/dev/null || true 

cp $HOME/.config/code-server/platform-shim.js ./code-server 2>/dev/null || true
cp $VSC_DIR/platform-shim.js ./code-server 2>/dev/null || true 

# shallow copy only
echo "Copying the android analytics.settings and default debug.keystore..."
cp $HOME/.android/* ./.android 2>/dev/null || true 

# touch "$TERMUX_PREFIX/etc/vscodetermux-dev-backup"

echo "Backup completed." && pwd
