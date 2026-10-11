#!/usr/bin/env bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
IMAGE_NAME="tvusage-builder"
TV_IP="192.168.0.102:5555"

echo "=== 1. Building Docker image for Android build ==="
docker build -t "$IMAGE_NAME" -f "$DIR/Dockerfile" "$DIR"

echo "=== 2. Compiling Release APK inside Docker container ==="
docker run --rm \
    -v "$DIR":/workspace \
    -v "$HOME/.gradle":/root/.gradle \
    -w /workspace \
    "$IMAGE_NAME" \
    ./gradlew assembleDebug --no-daemon

APK_PATH="$DIR/app/build/outputs/apk/debug/app-debug.apk"

if [ -f "$APK_PATH" ]; then
    echo "=== 3. APK build successful: $APK_PATH ==="
    echo "=== 4. Deploying to Android TV ($TV_IP) ==="
    adb connect "$TV_IP" || true
    adb -s "$TV_IP" install -r "$APK_PATH"
    
    echo "=== 5. Granting required permissions via ADB ==="
    adb -s "$TV_IP" shell appops set com.tvusage.free GET_USAGE_STATS allow
    adb -s "$TV_IP" shell appops set com.tvusage.free SYSTEM_ALERT_WINDOW allow
    adb -s "$TV_IP" shell dumpsys deviceidle whitelist +com.tvusage.free
    
    echo "=== 6. Launching TVUsage Free on TV ==="
    adb -s "$TV_IP" shell monkey -p com.tvusage.free -c android.intent.category.LAUNCHER 1
    
    echo "=== ВСЁ ГОТОВО! TVUsage Free успешно установлен и работает на ТВ ==="
else
    echo "Ошибка: APK не найден по пути $APK_PATH"
    exit 1
fi
