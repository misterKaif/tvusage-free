#!/usr/bin/env bash
# Script to restore all modified packages and settings to original state
TV_IP="192.168.0.102:5555"

echo "Restoring packages..."
PACKAGES=(
    "tv.samba.ssm"
    "com.sony.dtv.demosupport"
    "com.sony.dtv.demomode"
    "com.sony.dtv.multiscreendemo"
    "com.sony.dtv.promos"
    "com.sony.dtv.sonybugreportsys"
    "com.google.android.feedback"
    "com.google.android.tv.bugreportsender"
    "com.sony.dtv.youview"
    "com.youview.tv.servicehost"
    "com.sony.dtv.sonyselect"
    "com.sony.dtv.sonyselect.overlay",
    "com.sony.dtv.osat.music",
    "com.google.android.marvin.talkback",
    "com.google.android.play.games",
    "com.google.android.videos",
    "com.google.android.youtube.tvmusic"
)

for pkg in "${PACKAGES[@]}"; do
    echo "Enabling $pkg..."
    adb -s "$TV_IP" shell pm enable "$pkg"
done

echo "Restoring animation settings to 1.0..."
adb -s "$TV_IP" shell settings put global window_animation_scale 1.0
adb -s "$TV_IP" shell settings put global transition_animation_scale 1.0
adb -s "$TV_IP" shell settings put global animator_duration_scale 1.0

echo "Done! All settings and packages restored."
