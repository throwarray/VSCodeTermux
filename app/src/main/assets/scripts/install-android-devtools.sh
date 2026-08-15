#!/data/data/com.termux/files/usr/bin/bash

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

skip_if_marked "toolchain" "Android dev tools"

ENV_FILE="$HOME/.local/env"
NDK_VERSION=27.1.12297006
ANDROID_HOME="$TERMUX_PREFIX/opt/android-sdk"
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

step 1 "Update package lists"
pkg update -y -o Dpkg::Use-Pty=0 || { log_error "pkg update failed"; exit 1; }

# Manage gradle through pkg. security / version
step 2 "Install Android dev toolchain packages"
pkg install -y -o Dpkg::Use-Pty=0 -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" openjdk-17 aapt2 aapt apksigner aidl apkeditor dex2jar d8 apktool android-tools clang cmake ninja ndk-sysroot proot-distro gradle || \
  { log_error "pkg install failed"; exit 1; }

# openjdk-21 is transitive of gradle/cmake; 21 satisfies a 17 target fine. 
# Preferring it so VSCode extensions use it.
# The target version is still configured per-project. Fallback to 17 if 21 isn't present.

if [ -d "$TERMUX_PREFIX/lib/jvm/java-21-openjdk" ]; then
  JAVA_HOME="$TERMUX_PREFIX/lib/jvm/java-21-openjdk"
else
  JAVA_HOME="$TERMUX_PREFIX/lib/jvm/java-17-openjdk"
fi

# NOTE Termux repositories don't offer cmdline tools
# pkg install -y -o Dpkg::Use-Pty=0 -o Dpkg::Options::="--force-confdef" -o Dpkg::Options::="--force-confold" android-sdk-cmdline-tools 2>/dev/null || true

step 3 "Installing Android SDK cmdline-tools"
mkdir -p "$ANDROID_HOME/cmdline-tools"

if [ ! -x "$SDKMANAGER" ]; then
  # Check common locations, without relocate/symlink whole dir
  for candidate in \
    "$TERMUX_PREFIX/bin/sdkmanager" \
    "$TERMUX_PREFIX"/opt/android-sdk*/cmdline-tools/*/bin/sdkmanager; do
    if [ -x "$candidate" ]; then
      SDKMANAGER="$candidate"
      break
    fi
  done
fi

if [ ! -x "$SDKMANAGER" ]; then
  log_info "Downloading sdkmanager..."
  cd "$ANDROID_HOME/cmdline-tools"
  [ -d latest ] || {
    wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
    unzip -q cmdline-tools.zip
    mv cmdline-tools latest
    rm cmdline-tools.zip
  }
  SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
fi

step 4 "Appending Android dev vars to the shared env"

# APPENDS to preserve env (NPM_CONFIG_PREFIX/PATH/etc)
# (~/.local/env, written by install-toolchain.sh) 
# FIXME Should this use sed and/or a another file?

cat >> "$ENV_FILE" <<EOF
export JDK_HOME=$JAVA_HOME
export JAVA_HOME=$JAVA_HOME
export ANDROID_HOME=$ANDROID_HOME
export ANDROID_SDK_ROOT=$ANDROID_HOME
export ANDROID_USER_HOME=~/.android
export ANDROID_NDK_ROOT=$ANDROID_HOME/ndk/$NDK_VERSION
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/$NDK_VERSION

# JAVA_TOOL_OPTIONS is read by the JVM launcher itself.
# gradle.properties' .jvmargs doesn't reach every JVM context. 
# Fixes Kotlin's HostManager "Unknown hardware platform: arm" crash
# Error: (org.jetbrains.kotlin.konan.target.TargetSupportException) 
# 32-bit ARM: org.jetbrains.kotlin.konan.target.HostManager.hostArch()

export JAVA_TOOL_OPTIONS="-Dos.arch=x86_64 -Djava.net.preferIPv4Stack"

export PATH=\$JAVA_HOME/bin:$(dirname "$SDKMANAGER"):\$ANDROID_HOME/platform-tools:\$PATH

export GRADLE_HOME=$TERMUX_PREFIX/opt/gradle

# (multi-GB cache separate from the general home directory)
# export GRADLE_USER_HOME=TERMUX_PREFIX/opt/gradle-cache

export GRADLE_USER_HOME=~/.gradle
export GRADLE_OPTS="-Dorg.gradle.daemon=false"

EOF

source "$ENV_FILE"

step 5 "Pre-seeding SDK licenses"

mkdir -p "$ANDROID_HOME/licenses"
echo "8933bad161af4178b1185d1a37fbf41ea5269c55" > "$ANDROID_HOME/licenses/android-sdk-license"
echo "d56f5187479451eabf01fb78af6dfcb131a6481e" >> "$ANDROID_HOME/licenses/android-sdk-license"
echo "24333f8a63b6825ea9c5514f83c2829b004d1fee" >> "$ANDROID_HOME/licenses/android-sdk-license"
echo "84831b9409646a918e30573bab4c9c91346d8abd" > "$ANDROID_HOME/licenses/android-sdk-preview-license"

step 6 "Running sdkmanager license acceptance"
timeout 10 yes 2>/dev/null | "$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses > /dev/null 2>&1

step 7 "Installing Android SDK components"
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" "platform-tools" "platforms;android-34" "build-tools;34.0.0"

step "7a" "Pointing SDK build-tools at the ARM-native binaries"

# AGP/Gradle don't care whether the binary is the downloaded x86_64 one.

for BUILD_TOOLS_DIR in "$ANDROID_HOME"/build-tools/*/; do
  [ -d "$BUILD_TOOLS_DIR" ] || continue
  for pair in "aapt2:aapt2" "aapt:aapt" "zipalign:zipalign" "apksigner:apksigner" "d8:d8" "dx:dx" "aidl:aidl"; do
    sdk_name="${pair%%:*}"
    termux_name="${pair##*:}"
    termux_bin="$TERMUX_PREFIX/bin/$termux_name"
    if [ -x "$termux_bin" ]; then
      cp -f "$termux_bin" "$BUILD_TOOLS_DIR$sdk_name" 2>/dev/null || true
    fi
  done
  log_debug "Patched build-tools at $BUILD_TOOLS_DIR"
done

step "7b" "Installing SDK/NDK"
if [ "${SKIP_NDK:-}" = "1" ]; then
  log_warn "SKIP_NDK=1 set — skipping NDK install entirely"
else

# The version pinned here is coupled to the RN sample
# gradle/libs.versions.toml. If it ever changes, 
# this needs updating to match, they're not automatically kept in sync.

"$SDKMANAGER" --sdk_root="$ANDROID_HOME" "ndk;$NDK_VERSION"

step "7b2" "Slimming the NDK — this project only targets armeabi-v7a"

# Cleanup files not required for a build.
# The NDK ships for every target architecture (arm64, x86, x86_64) 
# even though this project only targets armeabi-v7a (minSdk 24).

NDK_ROOT_DIR="$ANDROID_HOME/ndk/$NDK_VERSION"
NDK_SYSROOT_LIB="$NDK_ROOT_DIR/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib"

if [ -d "$NDK_SYSROOT_LIB" ]; then
  before=$(du -sm "$NDK_ROOT_DIR" 2>/dev/null | cut -f1)
  for arch_dir in aarch64-linux-android i686-linux-android x86_64-linux-android riscv64-linux-android; do
    rm -rf "${NDK_SYSROOT_LIB:?}/$arch_dir" 2>/dev/null || true
  done

  # compiler-rt (sanitizer/builtins) libs, same story — per target-arch
  # subdirectories under lib/clang/<version>/lib/linux/.
  for clang_lib_dir in "$NDK_ROOT_DIR"/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/*/lib/linux; do
    [ -d "$clang_lib_dir" ] || continue
    find "$clang_lib_dir" -maxdepth 1 \( -name 'aarch64*' -o -name 'i386*' -o -name 'x86_64*' -o -name 'riscv64*' \) -exec rm -f {} + 2>/dev/null || true
  done

  # Tools irrelevant to building this project (shader compilation, CPU profiling.)
  rm -rf "$NDK_ROOT_DIR/shader-tools" "$NDK_ROOT_DIR/simpleperf" 2>/dev/null || true

  # Thin NDK bulk (~472MB of bin/'s 513MB total) 
  # none of these are part of a normal compile+link+package pipeline. clang-scan-deps/
  # clang-check/clang-tidy are static-analysis tools; clangd is a language
  # server; dsymutil/llvm-lipo are macOS-only; llvm-bolt/sancov/
  # llvm-cfi-verify are binary-optimization/sanitizer tools; llvm-ml is a
  # Windows MASM assembler; llvm-dwp/llvm-dwarfdump/llvm-objdump are
  # debug-info/disassembly tools, not needed to actually build anything.
  NDK_TOOLCHAIN_BIN_CLEANUP="$NDK_ROOT_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin"
  for unused_tool in clang-scan-deps clang-check clang-tidy llvm-bolt clangd \
                     dsymutil llvm-dwp llvm-cfi-verify llvm-objdump sancov \
                     llvm-ml llvm-lipo llvm-dwarfdump; do
    rm -f "$NDK_TOOLCHAIN_BIN_CLEANUP/$unused_tool" 2>/dev/null || true
  done
  after=$(du -sm "$NDK_ROOT_DIR" 2>/dev/null | cut -f1)
  log_ok "NDK slimmed: ${before:-?}MB -> ${after:-?}MB"
else
  log_warn "expected NDK sysroot lib path not found — skipping slimming"
fi

step "7c" "Pointing the NDK's toolchain at Termux's own ARM-native clang"

# The NDK's own compiler binaries (toolchains/llvm/prebuilt/linux-x86_64/bin/*) 
# are built for x86_64 Linux. Termux's own clang is given --target the NDK's sysroot

NDK_TOOLCHAIN_BIN="$ANDROID_HOME/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ -d "$NDK_TOOLCHAIN_BIN" ] && [ -x "$TERMUX_PREFIX/bin/clang" ]; then
  NDK_SYSROOT="$ANDROID_HOME/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/sysroot"

  # Generic (unprefixed) tools — plain copies, no target-triple needed.
  for tool in clang clang++ llvm-ar llvm-ranlib llvm-strip llvm-objcopy llvm-nm llvm-readelf; do
    termux_tool="$TERMUX_PREFIX/bin/$tool"
    [ -x "$termux_tool" ] && cp -f "$termux_tool" "$NDK_TOOLCHAIN_BIN/$tool" 2>/dev/null || true
  done
  [ -x "$TERMUX_PREFIX/bin/ld.lld" ] && cp -f "$TERMUX_PREFIX/bin/ld.lld" "$NDK_TOOLCHAIN_BIN/ld.lld" 2>/dev/null || true

  # Every arch wrapper script the NDK ships and every
  # armv7a-linux-androideabi<N>-clang{,++} for N != 24) is now unused — 
  find "$NDK_TOOLCHAIN_BIN" -maxdepth 1 \
    \( -name 'aarch64-linux-android*' -o -name 'x86_64-linux-android*' \
       -o -name 'i686-linux-android*' -o -name 'riscv64-linux-android*' \) \
    -exec rm -f {} + 2>/dev/null || true
  for api in 21 22 23 25 26 27 28 29 30 31 32 33 34 35; do
    rm -f "$NDK_TOOLCHAIN_BIN/armv7a-linux-androideabi${api}-clang" \
          "$NDK_TOOLCHAIN_BIN/armv7a-linux-androideabi${api}-clang++" 2>/dev/null || true
  done

  # TODO: Examples using target armeabi-v7a at minSdk 24
  # if that ever changes, this needs updating to match.
  
  # CMake's android.toolchain.cmake invokes by name.
  # Script forwarding to Termux's clang with --target flag and 
  # the NDK's sysroot, instead of the NDK's wrapper.
  # minSdk=24 matches this project's gradle/libs.versions.toml

  for pair in "armv7a-linux-androideabi24-clang:armv7a-linux-androideabi24" \
              "armv7a-linux-androideabi24-clang++:armv7a-linux-androideabi24"; do
    wrapper_name="${pair%%:*}"
    target_triple="${pair##*:}"
    is_cxx=""
    case "$wrapper_name" in *clang++) is_cxx="++" ;; esac
    cat > "$NDK_TOOLCHAIN_BIN/$wrapper_name" <<WRAPPER_EOF
#!/data/data/com.termux/files/usr/bin/bash
exec "$TERMUX_PREFIX/bin/clang$is_cxx" --target=$target_triple --sysroot="$NDK_SYSROOT" "\$@"
WRAPPER_EOF
    chmod +x "$NDK_TOOLCHAIN_BIN/$wrapper_name"
  done

  # musl/ is used internally by Google when building the NDK's own clang
  # python3 is already on PATH and takes priority.
  rm -rf "$ANDROID_HOME/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/musl" \
         "$ANDROID_HOME/ndk/$NDK_VERSION/toolchains/llvm/prebuilt/linux-x86_64/python3" \
         2>/dev/null || true

  log_ok "NDK toolchain pointed to Termux's own clang."
else
  log_warn "skipping NDK toolchain symlinking — Termux clang or NDK toolchain dir not found"
fi
fi # end SKIP_NDK guard

step 8 "Writing global Gradle config for guest builds"

# For Android projects the user builds INSIDE this app.
# Written to GRADLE_USER_HOME (The configured cache dir), 

GRADLE_USER_HOME_DIR="$TERMUX_PREFIX/opt/gradle-cache"
mkdir -p "$GRADLE_USER_HOME_DIR"
cat > "$GRADLE_USER_HOME_DIR/gradle.properties" <<EOF

# AGP fetches aapt2 for Linux/macOS/Windows. 
# Using pkg-installed aapt2 instead.

android.aapt2FromMavenOverride=$TERMUX_PREFIX/bin/aapt2

# (e.g. react-native-gradle-plugin's settings-plugin build)
# Gradle fails with "Cannot find a Java installation". Telling Gradle where the JDKs live.

org.gradle.java.installations.paths=$TERMUX_PREFIX/lib/jvm/java-17-openjdk,$TERMUX_PREFIX/lib/jvm/java-21-openjdk
org.gradle.java.installations.auto-detect=false
org.gradle.java.installations.auto-download=false

# Apply conservative memory / verbosity tuning
# Otherwise build attempts may get killed by the OS.

org.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=256m -XX:+UseSerialGC
org.gradle.daemon=false
org.gradle.parallel=false
org.gradle.caching=false
org.gradle.workers.max=1
org.gradle.logging.level=warn
org.gradle.console=plain

kotlin.compiler.execution.strategy=in-process
kotlin.incremental=false

EOF

step 9 "Writing toolchain marker"
mark_done "toolchain"

step 10 "Cleaning up temporary install files"
rm -rf "$ANDROID_HOME/tmp" 2>/dev/null || true
pkg clean || true
pkg autoclean

log_ok "Android dev tools install complete"
