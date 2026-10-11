package com.tvusage.free.util;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * Helper utility for TV power/sleep management and media muting.
 */
public class TvControlHelper {

    /**
     * Puts the TV screen into sleep / standby mode.
     */
    public static boolean sleepTv(Context context) {
        // 1. Immediately pause and mute active audio/video
        pauseAndMuteMedia(context);

        boolean success = false;

        // 2. Try Device Policy Manager (Standard lockNow turns screen off immediately)
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(context, TvDeviceAdminReceiver.class);
            if (dpm != null && dpm.isAdminActive(adminComponent)) {
                dpm.lockNow();
                success = true;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 3. Fallback: Dispatch POWER / SLEEP key event via shell or broadcast
        if (!success) {
            try {
                // Return to home screen first
                Intent home = new Intent(Intent.ACTION_MAIN);
                home.addCategory(Intent.CATEGORY_HOME);
                home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(home);

                // Send keyevent 26 (POWER) or 223 (SLEEP) via runtime exec (works if granted adb or root)
                Runtime.getRuntime().exec("input keyevent 223");
                success = true;
            } catch (Exception ignored) {
                try {
                    Runtime.getRuntime().exec("input keyevent 26");
                    success = true;
                } catch (Exception e2) {
                    e2.printStackTrace();
                }
            }
        }

        return success;
    }

    /**
     * Pauses playing media across the system.
     */
    public static void pauseAndMuteMedia(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                long now = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
