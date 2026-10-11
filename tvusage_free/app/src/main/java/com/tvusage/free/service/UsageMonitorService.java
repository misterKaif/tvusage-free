package com.tvusage.free.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.tvusage.free.bot.TelegramWorker;
import com.tvusage.free.ui.LockActivity;
import com.tvusage.free.util.AppPreferences;
import com.tvusage.free.web.LocalWebServer;

public class UsageMonitorService extends Service {
    private static final String CHANNEL_ID = "tvusage_free_service";
    private static final int NOTIF_ID = 1001;
    private static final long CHECK_INTERVAL_MS = 2000;

    private AppPreferences prefs;
    private UsageStatsManager usageStatsManager;
    private Handler handler;
    private LocalWebServer webServer;
    private TelegramWorker telegramWorker;
    private static volatile String currentForegroundPackage = "";
    private static volatile boolean isScreenOn = true;

    public static boolean isTvScreenOn() {
        return isScreenOn;
    }

    public static String getActivePackage() {
        return currentForegroundPackage;
    }

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                isScreenOn = true;
            } else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                isScreenOn = false;
                // When TV sleeps, reset the temporary adult unlock
                if (prefs != null) {
                    prefs.setUnlockedUntilSleep(false);
                }
            }
        }
    };

    private final Runnable monitorRunnable = new Runnable() {
        @Override
        public void run() {
            checkCurrentAppAndEnforceLimits();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new AppPreferences(this);
        usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        handler = new Handler(Looper.getMainLooper());

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);

        startForeground(NOTIF_ID, createNotification());

        // Start local Web Server for QR configuration
        webServer = new LocalWebServer(this, 8080);
        webServer.startServer();

        // Start Telegram Bot Long Polling
        telegramWorker = new TelegramWorker(this);
        telegramWorker.startPolling();

        handler.post(monitorRunnable);
    }

    private void checkCurrentAppAndEnforceLimits() {
        if (!isScreenOn) return;

        String topPackage = getForegroundPackage();
        if (topPackage == null || topPackage.isEmpty()) return;

        // Skip our own app activities
        if (topPackage.equals(getPackageName())) return;

        currentForegroundPackage = topPackage;

        // Check if the current foreground app has a time limit
        if (prefs.isPackageRestricted(topPackage)) {
            // If adult already unlocked for this session, skip check
            if (prefs.isUnlockedUntilSleep()) {
                return;
            }

            // Account for active watching time (+2 seconds)
            prefs.addUsageSeconds(2);

            // If time is expired -> trigger Lock Screen immediately!
            if (prefs.isTimeExpired()) {
                Intent lockIntent = new Intent(this, LockActivity.class);
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                lockIntent.putExtra(LockActivity.EXTRA_BLOCKED_PACKAGE, topPackage);
                startActivity(lockIntent);
            }
        }
    }

    private String getForegroundPackage() {
        long end = System.currentTimeMillis();
        long start = end - 5000;
        UsageEvents events = usageStatsManager.queryEvents(start, end);
        UsageEvents.Event event = new UsageEvents.Event();
        String lastApp = null;

        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (event.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastApp = event.getPackageName();
            }
        }
        return lastApp != null ? lastApp : currentForegroundPackage;
    }

    public String getCurrentForegroundPackage() {
        return currentForegroundPackage;
    }

    private Notification createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "TVUsage Free Monitor",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TVUsage Free")
                .setContentText("Родительский контроль активен")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (handler != null) handler.removeCallbacks(monitorRunnable);
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {}
        if (webServer != null) webServer.stopServer();
        if (telegramWorker != null) telegramWorker.stopPolling();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
