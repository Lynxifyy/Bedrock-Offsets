#!/usr/bin/env bash
# Installs the local build toolchain used by this project on a bare Debian/Ubuntu host.
#
# It is NOT needed on GitHub-hosted runners: the CI workflow installs the Android
# SDK and the Rust target itself. Run this when you need a local build environment.
#
# Everything is installed under a single prefix so the host stays clean.
set -euo pipefail

PREFIX="${PREFIX:-/workspace}"
TOOLS="$PREFIX/tools"
SDK="$PREFIX/android-sdk"

JDK_VERSION="21"
JDK_DIR="$TOOLS/jdk-21.0.13+9"
NDK_VERSION="27.3.13750724"
GRADLE_VERSION="8.13"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"
RUST_TARGETS="aarch64-linux-android x86_64-linux-android"

mkdir -p "$TOOLS" "$SDK/cmdline-tools" "$PREFIX/dl"
cd "$PREFIX/dl"

echo "==> JDK $JDK_VERSION"
if [ ! -d "$JDK_DIR" ]; then
  curl -sL -o jdk.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/$JDK_VERSION/ga/linux/x64/jdk/hotspot/normal/eclipse"
  tar xzf jdk.tar.gz -C "$TOOLS"
fi

export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> Android command-line tools"
if [ ! -d "$SDK/cmdline-tools/latest" ]; then
  curl -sL -o cmdline-tools.zip \
    "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP"
  unzip -q -o cmdline-tools.zip -d "$SDK/cmdline-tools-tmp"
  mv "$SDK/cmdline-tools-tmp/cmdline-tools" "$SDK/cmdline-tools/latest"
  rmdir "$SDK/cmdline-tools-tmp"
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export PATH="$SDK/cmdline-tools/latest/bin:$PATH"

yes | sdkmanager --licenses >/dev/null
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" \
  "ndk;$NDK_VERSION" "cmake;3.31.6"
export ANDROID_NDK_HOME="$SDK/ndk/$NDK_VERSION"

echo "==> Rust"
export RUSTUP_HOME="$TOOLS/rustup"
export CARGO_HOME="$TOOLS/cargo"
if [ ! -x "$CARGO_HOME/bin/cargo" ]; then
  curl -sSf -o rustup-init \
    https://static.rust-lang.org/rustup/dist/x86_64-unknown-linux-gnu/rustup-init
  chmod +x rustup-init
  ./rustup-init -y --no-modify-path --default-toolchain stable --profile minimal
fi
export PATH="$CARGO_HOME/bin:$PATH"
rustup target add $RUST_TARGETS
rustup component add clippy rustfmt
cargo install cargo-ndk --locked
cargo install cargo-audit --locked

echo "==> Gradle"
if [ ! -d "$TOOLS/gradle-$GRADLE_VERSION" ]; then
  curl -sL -o gradle.zip "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
  unzip -q -o gradle.zip -d "$TOOLS"
fi

cat <<EOF

Toolchain ready. Add this to your shell profile:

  export JAVA_HOME=$JDK_DIR
  export ANDROID_HOME=$SDK
  export ANDROID_NDK_HOME=$ANDROID_NDK_HOME
  export RUSTUP_HOME=$TOOLS/rustup
  export CARGO_HOME=$TOOLS/cargo
  export PATH=$TOOLS/gradle-$GRADLE_VERSION/bin:\$JAVA_HOME/bin:\$CARGO_HOME/bin:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools:\$PATH

Then build with: ./gradlew test
EOF
