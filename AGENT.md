# KidTVLock (TVUsage Free) — Agent Notes

## Build & Deployment Workflow

### 1. Local Docker Build
A preconfigured local Docker image `tvusage-builder:latest` contains the complete Android SDK and JDK 17 environment.

To build the APK locally without installing Android SDK on the host machine:
```bash
docker run --rm -v "$PWD/tvusage_free":/project -w /project tvusage-builder:latest ./gradlew assembleDebug
```
Built APK location:
`tvusage_free/app/build/outputs/apk/debug/app-debug.apk`

### 2. ADB Install & TV Testing
TV IP on local Wi-Fi: `192.168.0.102:5555`

To connect and install the APK directly to the TV:
```bash
adb connect 192.168.0.102:5555
adb install -r tvusage_free/app/build/outputs/apk/debug/app-debug.apk
```

To launch the app on the TV:
```bash
adb shell am start -n com.tvusage.free/.ui.MainActivity
```

To restart the background service:
```bash
adb shell am start-foreground-service com.tvusage.free/.service.UsageMonitorService
```

### 3. Remote Standby / Power Management
To test TV sleep / standby command:
```bash
adb shell input keyevent 223   # KEYCODE_SLEEP
```
