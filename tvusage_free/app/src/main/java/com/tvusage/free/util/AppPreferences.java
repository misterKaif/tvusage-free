package com.tvusage.free.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Manages persistent storage for TV screen time rules, PIN codes, and session unlocks.
 *
 * Architecture details:
 * - Parent Master PIN (KEY_PARENT_PIN): When entered on the lock screen or web UI,
 *   completely unlocks the TV without restrictions until the TV is turned off / enters sleep.
 * - Child / Extend PIN (KEY_CHILD_EXTEND_PIN): Optional PIN to add +15/+30 minutes of bonus time.
 * - Unlocked Until Sleep (KEY_UNLOCKED_UNTIL_SLEEP): Boolean state reset on ACTION_SCREEN_OFF.
 */
public class AppPreferences {
    private static final String PREF_NAME = "tvusage_free_prefs";

    // Master Parent PIN to bypass all restrictions until TV sleeps (e.g. "UP,UP,DOWN,DOWN")
    private static final String KEY_PARENT_PIN = "parent_pin";

    // Baseline daily screen time limit in minutes for the child (e.g. 45 min, 10 min, 1 min)
    private static final String KEY_DAILY_LIMIT_MINUTES = "daily_limit_minutes";

    // Protected application package names subject to the shared timer
    private static final String KEY_RESTRICTED_PACKAGES = "restricted_packages";

    // Date stamp (yyyyMMdd) for midnight usage reset
    private static final String KEY_USAGE_DATE = "usage_date";

    // Accumulated usage seconds today across protected apps
    private static final String KEY_TODAY_USAGE_SECONDS = "today_usage_seconds";

    // Bonus minutes granted today on top of the base limit
    private static final String KEY_BONUS_MINUTES = "bonus_minutes";

    // True when parent entered parent PIN or clicked unlock in Web UI; cleared on TV sleep
    private static final String KEY_UNLOCKED_UNTIL_SLEEP = "unlocked_until_sleep";

    // Telegram bot credentials
    private static final String KEY_TG_BOT_TOKEN = "tg_bot_token";
    private static final String KEY_TG_CHAT_ID = "tg_chat_id";

    private final SharedPreferences prefs;

    public AppPreferences(Context context) {
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Parent PIN: Bypasses all locks until TV sleep. Default: UP,UP,DOWN,DOWN.
     */
    public String getParentPin() {
        return prefs.getString(KEY_PARENT_PIN, "UP,UP,DOWN,DOWN");
    }

    public void setParentPin(String pin) {
        prefs.edit().putString(KEY_PARENT_PIN, pin).apply();
    }

    public int getDailyLimitMinutes() {
        return prefs.getInt(KEY_DAILY_LIMIT_MINUTES, 45);
    }

    public void setDailyLimitMinutes(int minutes) {
        prefs.edit().putInt(KEY_DAILY_LIMIT_MINUTES, Math.max(0, minutes)).apply();
    }

    public int getBonusMinutes() {
        checkAndResetDailyUsage();
        return prefs.getInt(KEY_BONUS_MINUTES, 0);
    }

    public synchronized void addBonusMinutes(int minutes) {
        checkAndResetDailyUsage();
        int cur = prefs.getInt(KEY_BONUS_MINUTES, 0);
        prefs.edit().putInt(KEY_BONUS_MINUTES, Math.max(0, cur + minutes)).apply();
    }

    /**
     * Calculates total allowed minutes today = Base Limit + Bonus Minutes.
     * Returns 0 if base limit is set to 0 (unlimited).
     */
    public int getTotalEffectiveLimitMinutes() {
        int base = getDailyLimitMinutes();
        if (base <= 0) return 0;
        return base + getBonusMinutes();
    }

    public Set<String> getRestrictedPackages() {
        Set<String> defaults = new HashSet<>();
        defaults.add("org.smarttube.stable");
        defaults.add("com.google.android.youtube.tv");
        defaults.add("ru.kinopoisk.tv");
        defaults.add("ru.astroapps.hdrezka");
        return prefs.getStringSet(KEY_RESTRICTED_PACKAGES, defaults);
    }

    public void setRestrictedPackages(Set<String> pkgs) {
        prefs.edit().putStringSet(KEY_RESTRICTED_PACKAGES, pkgs).apply();
    }

    public boolean isPackageRestricted(String pkg) {
        return getRestrictedPackages().contains(pkg);
    }

    public synchronized int getTodayUsageSeconds() {
        checkAndResetDailyUsage();
        return prefs.getInt(KEY_TODAY_USAGE_SECONDS, 0);
    }

    public synchronized void addUsageSeconds(int seconds) {
        checkAndResetDailyUsage();
        int current = prefs.getInt(KEY_TODAY_USAGE_SECONDS, 0);
        prefs.edit().putInt(KEY_TODAY_USAGE_SECONDS, current + seconds).apply();
    }

    public synchronized void resetTodayUsage() {
        prefs.edit()
                .putInt(KEY_TODAY_USAGE_SECONDS, 0)
                .putInt(KEY_BONUS_MINUTES, 0)
                .apply();
    }

    public synchronized void addGrantMinutes(int minutes) {
        addBonusMinutes(minutes);
    }

    /**
     * Time is expired when:
     * - Parent mode is NOT active (isUnlockedUntilSleep is false)
     * - Limit is > 0
     * - Used seconds exceed allowed seconds
     */
    public boolean isTimeExpired() {
        if (isUnlockedUntilSleep()) return false;
        int totalLimit = getTotalEffectiveLimitMinutes();
        if (totalLimit <= 0) return false;
        int usedSec = getTodayUsageSeconds();
        return usedSec >= (totalLimit * 60);
    }

    public int getRemainingMinutes() {
        int totalLimit = getTotalEffectiveLimitMinutes();
        if (totalLimit <= 0) return 999;
        int usedMin = getTodayUsageSeconds() / 60;
        return Math.max(0, totalLimit - usedMin);
    }

    /**
     * Checks if TV is currently in Adult Parent Mode (unlimited until screen off).
     */
    public boolean isUnlockedUntilSleep() {
        return prefs.getBoolean(KEY_UNLOCKED_UNTIL_SLEEP, false);
    }

    /**
     * Enables or disables Parent Mode until screen turns off.
     */
    public void setUnlockedUntilSleep(boolean unlocked) {
        prefs.edit().putBoolean(KEY_UNLOCKED_UNTIL_SLEEP, unlocked).apply();
    }

    public String getTgBotToken() {
        return prefs.getString(KEY_TG_BOT_TOKEN, "");
    }

    public void setTgBotToken(String token) {
        prefs.edit().putString(KEY_TG_BOT_TOKEN, token).apply();
    }

    public String getTgChatId() {
        return prefs.getString(KEY_TG_CHAT_ID, "");
    }

    public void setTgChatId(String chatId) {
        prefs.edit().putString(KEY_TG_CHAT_ID, chatId).apply();
    }

    public boolean isTelegramConfigured() {
        return !getTgBotToken().isEmpty() && !getTgChatId().isEmpty();
    }

    private void checkAndResetDailyUsage() {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        String savedDate = prefs.getString(KEY_USAGE_DATE, "");
        if (!today.equals(savedDate)) {
            prefs.edit()
                    .putString(KEY_USAGE_DATE, today)
                    .putInt(KEY_TODAY_USAGE_SECONDS, 0)
                    .putInt(KEY_BONUS_MINUTES, 0)
                    .putBoolean(KEY_UNLOCKED_UNTIL_SLEEP, false)
                    .apply();
        }
    }
}
