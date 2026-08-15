#!/data/data/com.termux/files/usr/bin/bash

# Restores the backup files to their source destinations.

TERMUX_PREFIX=/data/data/com.termux/files/usr

INSTALLED_MARKER=$TERMUX_PREFIX/etc/vscodetermux-base-installed

DIR="$(cd "$(dirname "$0")" && pwd)"

VSC_DIR=$HOME/.local/share/code-server

if [ ! -f "$INSTALLED_MARKER" ]; then
  echo "Unknown platform or the bootstrap not finished."
  exit 1
fi

cd $DIR/backup

# NOTE: /sdcard doesn't have executable permissions. can't just ln the dirs.

echo "Restoring scripts and examples"
echo $HOME

cp -r ./examples $HOME

cp -r ./scripts/* $PREFIX/libexec/vscodetermux

echo "Restoring VSCode settings and extensions
echo $VSC_DIR"

# NOTE: localized vs default ~/.config/code-server/config.yaml
cp ./code-server/config.yaml $VSC_DIR
cp ./code-server/platform-shim.js $VSC_DIR
cp ./code-server/User/settings.json $VSC_DIR/User
cp -r ./code-server/extensions $VSC_DIR 

echo "Restoring the android analytics.settings and *.keystore (debug|release)."
cp -r ./.android/* $HOME/.android 2>/dev/null || true 

echo "DONE. Restoration successful."