#!/data/data/com.termux/files/usr/bin/bash

# Builds the APK and keeps a timestamped backup copy alongside a full build
# log — run via `sh ./dev/build.sh [debug|release]`.
#
# Written to be POSIX-sh safe (no arrays, no PIPESTATUS, no [[ ]]) since
# it's invoked with `sh`, not `bash` — the shebang above isn't honored when
# a script is run as `sh script.sh` rather than `./script.sh`.

DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR/../"

BUILD_TYPE="${1:-debug}"
BUILD_TYPE_TITLECASE="$(printf '%s' "$BUILD_TYPE" | sed 's/^\(.\)/\U\1/')"

OUTPUT="./app/build/outputs/apk/$BUILD_TYPE/app-$BUILD_TYPE.apk"
LOG_FILE="$DIR/build.log"

# Redirected (not piped through tee) so the exit code below is gradle's own,
# not tee's — `cmd | tee file; $?` would give tee's status under plain sh,
# since PIPESTATUS is a bashism this script can't rely on here.
gradle "assemble${BUILD_TYPE_TITLECASE}" --no-daemon --stacktrace \
  > "$LOG_FILE" 2>&1
GRADLE_EXIT=$?

cat "$LOG_FILE"

if [ "$GRADLE_EXIT" != "0" ]; then
  echo "Build failed (exit $GRADLE_EXIT) — full log: $LOG_FILE"
  exit 1
fi

if [ ! -f "$OUTPUT" ]; then
  echo "Output APK not found. See $LOG_FILE"
  exit 1
fi

# copy of the apk. i.e app-release_epoch.apk
OUTPUT_BACKUP="$(dirname "$OUTPUT")/app-${BUILD_TYPE}_$(date +%s).apk"

cp "$OUTPUT" "$OUTPUT_BACKUP"

export OUTPUT_BACKUP

echo "$OUTPUT_BACKUP"
