package com.tvusage.free.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.tvusage.free.R;
import com.tvusage.free.bot.TelegramWorker;
import com.tvusage.free.util.AppPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * LockActivity presents the modal fullscreen parental lock screen on the TV.
 *
 * Matching kidtvlock.png:
 * - Minimalist dark background (#12151B)
 * - Headline "Enter current password"
 * - 6 circular indicators that fill green upon D-Pad arrow press
 * - Master password is NEVER revealed anywhere in text.
 *
 * Unlock behavior:
 * - When Parent PIN is entered:
 *   1) If opened from Settings (EXTRA_UNLOCK_FOR_SETTINGS): allows parent into settings, but does NOT disable child limits.
 *   2) If opened because time expired: activates Parent Mode (unlimited access until TV sleeps / turns off).
 */
public class LockActivity extends AppCompatActivity {
    public static final String EXTRA_BLOCKED_PACKAGE = "extra_blocked_package";
    public static final String EXTRA_UNLOCK_FOR_SETTINGS = "extra_unlock_for_settings";

    private AppPreferences prefs;
    private final List<String> enteredSequence = new ArrayList<>();
    private View[] dots;
    private Button btnAskTime;
    private boolean isUnlockForSettings = false;
    public static final String ACTION_TIME_CHANGED = "com.tvusage.free.ACTION_TIME_CHANGED";
    public static final String EXTRA_GRANTED_MESSAGE = "extra_granted_message";

    private final android.content.BroadcastReceiver unlockReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            if (ACTION_TIME_CHANGED.equals(intent.getAction())) {
                String msg = intent.getStringExtra(EXTRA_GRANTED_MESSAGE);
                if (msg == null || msg.isEmpty()) {
                    msg = "Время добавлено родителями! Приятного просмотра!";
                }
                Toast.makeText(LockActivity.this, msg, Toast.LENGTH_LONG).show();
                finish();
            }
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_lock);

        prefs = new AppPreferences(this);
        isUnlockForSettings = getIntent().getBooleanExtra(EXTRA_UNLOCK_FOR_SETTINGS, false);

        android.content.IntentFilter filter = new android.content.IntentFilter(ACTION_TIME_CHANGED);
        registerReceiver(unlockReceiver, filter);

        dots = new View[]{
                findViewById(R.id.dot1),
                findViewById(R.id.dot2),
                findViewById(R.id.dot3),
                findViewById(R.id.dot4),
                findViewById(R.id.dot5),
                findViewById(R.id.dot6)
        };

        btnAskTime = findViewById(R.id.btnAskTime);
        if (prefs.isTelegramConfigured() && !isUnlockForSettings) {
            btnAskTime.setVisibility(View.VISIBLE);
            btnAskTime.setOnClickListener(v -> {
                TelegramWorker.sendAskTimeRequest(LockActivity.this);
                Toast.makeText(LockActivity.this, "Запрос отправлен родителям в Telegram!", Toast.LENGTH_LONG).show();
                btnAskTime.setEnabled(false);
                btnAskTime.setText("Запрос отправлен...");
            });
        } else {
            btnAskTime.setVisibility(View.GONE);
        }

        // Pause playing media and stop background processes of the blocked app
        if (!isUnlockForSettings) {
            stopPlayingMediaAndKillApp(getIntent().getStringExtra(EXTRA_BLOCKED_PACKAGE));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(unlockReceiver);
        } catch (Exception ignored) {}
    }

    /**
     * Pauses media playback (video/audio players like YouTube/SmartTube)
     * and kills background processes of the restricted app.
     */
    private void stopPlayingMediaAndKillApp(String blockedPackage) {
        try {
            // 1. Request permanent audio focus to force active video/music players to release audio hardware
            android.media.AudioManager audioManager = (android.media.AudioManager) getSystemService(AUDIO_SERVICE);
            if (audioManager != null) {
                audioManager.requestAudioFocus(
                        null,
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.AUDIOFOCUS_GAIN
                );
            }

            // 2. Dispatch global pause and stop media key events to halt background players
            long eventTime = android.os.SystemClock.uptimeMillis();
            if (audioManager != null) {
                audioManager.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
                audioManager.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
                audioManager.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP, 0));
                audioManager.dispatchMediaKeyEvent(new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP, 0));
            }

            // 3. Terminate background processes for the target blocked package
            if (blockedPackage != null && !blockedPackage.isEmpty() && !blockedPackage.equals(getPackageName())) {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                if (am != null) {
                    am.killBackgroundProcesses(blockedPackage);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        String arrow = null;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP:
                arrow = "UP";
                break;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                arrow = "DOWN";
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                arrow = "LEFT";
                break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                arrow = "RIGHT";
                break;
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_BACK:
                handleCancel();
                return true;
        }

        if (arrow != null) {
            handleArrowInput(arrow);
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    private void handleArrowInput(String arrow) {
        if (enteredSequence.size() >= dots.length) {
            resetDots();
        }

        enteredSequence.add(arrow);
        updateDotsUI();

        String currentPin = String.join(",", enteredSequence);
        String targetParentPin = prefs.getParentPin();

        if (currentPin.equals(targetParentPin)) {
            if (isUnlockForSettings) {
                // Parent opened settings on TV: only authorize settings, do NOT disable limits for apps
                setResult(RESULT_OK);
                Toast.makeText(this, "Вход в настройки разрешен", Toast.LENGTH_SHORT).show();
                finish();
            } else {
                // Parent entered PIN on lock screen: enable Parent Mode until TV sleeps!
                prefs.setUnlockedUntilSleep(true);
                Toast.makeText(this, "Режим родителя: без ограничений до выключения ТВ", Toast.LENGTH_LONG).show();
                finish();
            }
        } else if (enteredSequence.size() >= targetParentPin.split(",").length) {
            // Sequence length reached but PIN is incorrect
            Toast.makeText(this, "Неверный пароль", Toast.LENGTH_SHORT).show();
            resetDots();
        }
    }

    private void updateDotsUI() {
        for (int i = 0; i < dots.length; i++) {
            if (i < enteredSequence.size()) {
                dots[i].setBackgroundResource(R.drawable.pin_dot_filled);
            } else {
                dots[i].setBackgroundResource(R.drawable.pin_dot_empty);
            }
        }
    }

    private void resetDots() {
        enteredSequence.clear();
        for (View dot : dots) {
            dot.setBackgroundResource(R.drawable.pin_dot_empty);
        }
    }

    private void handleCancel() {
        if (isUnlockForSettings) {
            setResult(RESULT_CANCELED);
            finish();
        } else {
            // Child canceled on lock screen: ensure app is stopped and return to TV launcher home screen
            String blockedPackage = getIntent().getStringExtra(EXTRA_BLOCKED_PACKAGE);
            if (blockedPackage != null && !blockedPackage.isEmpty()) {
                try {
                    android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                    if (am != null) {
                        am.killBackgroundProcesses(blockedPackage);
                    }
                } catch (Exception ignored) {}
            }

            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
            finish();
        }
    }
}
