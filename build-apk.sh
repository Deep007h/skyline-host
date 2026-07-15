#!/bin/bash
set -e

echo "[1/7] Initializing project paths..."
PROJECT_DIR="/home/deep/Music/newregal-main/NEWREGAL/skyline-host"
SDK_DIR="$PROJECT_DIR/android-sdk"
export ANDROID_HOME="$SDK_DIR"
export PATH="$PATH:$SDK_DIR/cmdline-tools/latest/bin:$SDK_DIR/platform-tools"

echo "[2/7] Installing system dependencies (unzip)..."
echo "1234" | sudo -S pacman -S --noconfirm unzip || true

mkdir -p "$SDK_DIR"

if [ ! -d "$SDK_DIR/cmdline-tools" ]; then
    echo "[3/7] Downloading Android Command Line Tools..."
    cd "$SDK_DIR"
    curl -L https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -o cmdline.zip
    echo "Extracting tools..."
    unzip -q cmdline.zip
    
    # The zip extracts to cmdline-tools/
    # We must move it to cmdline-tools/latest to meet sdkmanager requirements
    mkdir -p cmdline-tools/latest
    mv cmdline-tools/bin cmdline-tools/latest/ || true
    mv cmdline-tools/lib cmdline-tools/latest/ || true
    mv cmdline-tools/source.properties cmdline-tools/latest/ || true
    rm -f cmdline.zip
fi

echo "[4/7] Accepting Android licenses..."
# Accept licenses automatically
yes | "$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --licenses --sdk_root="$SDK_DIR" || true

echo "[5/7] Installing Android Platform 33 and Build Tools..."
"$SDK_DIR/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK_DIR" "platform-tools" "platforms;android-33" "build-tools;33.0.2"

echo "[6/7] Preparing Capacitor Android Project..."
cd "$PROJECT_DIR"

# Install Capacitor version 5 (fully compatible with JDK 17)
npm install @capacitor/core@5 @capacitor/cli@5 @capacitor/android@5 --no-audit --no-fund

# Initialize Capacitor project if android folder does not exist
if [ ! -d "android" ]; then
    npx cap init "Skyline Host" "com.skyline.host" --web-dir=public
    npx cap add android
fi

# Copy web assets
npx cap copy

echo "[7/7] Compiling native APK..."
cd android
# Set Android Home locally for Gradle build
echo "sdk.dir=$SDK_DIR" > local.properties
./gradlew assembleDebug

echo "=========================================="
echo "   APK Compiled Successfully!             "
echo "   Location: $PROJECT_DIR/android/app/build/outputs/apk/debug/app-debug.apk"
echo "=========================================="
