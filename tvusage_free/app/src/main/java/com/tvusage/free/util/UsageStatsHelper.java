package com.tvusage.free.util;

import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UsageStatsHelper {

    public static class AppStatItem {
        public String packageName;
        public String label;
        public long totalTimeMs;
        public boolean isRestricted;

        public AppStatItem(String packageName, String label, long totalTimeMs, boolean isRestricted) {
            this.packageName = packageName;
            this.label = label;
            this.totalTimeMs = totalTimeMs;
            this.isRestricted = isRestricted;
        }

        public String getFormattedTime() {
            long totalMinutes = totalTimeMs / (1000 * 60);
            long hours = totalMinutes / 60;
            long minutes = totalMinutes % 60;
            if (hours > 0) {
                return hours + " ч " + minutes + " мин";
            } else {
                return minutes + " мин";
            }
        }
    }

    public enum Period {
        DAY,
        WEEK,
        MONTH
    }

    public static List<AppStatItem> getAppStats(Context context, Period period) {
        UsageStatsManager usm = (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
        PackageManager pm = context.getPackageManager();
        AppPreferences prefs = new AppPreferences(context);

        Calendar cal = Calendar.getInstance();
        long endTime = cal.getTimeInMillis();

        int interval;
        switch (period) {
            case WEEK:
                cal.add(Calendar.DAY_OF_YEAR, -7);
                interval = UsageStatsManager.INTERVAL_WEEKLY;
                break;
            case MONTH:
                cal.add(Calendar.DAY_OF_YEAR, -30);
                interval = UsageStatsManager.INTERVAL_MONTHLY;
                break;
            case DAY:
            default:
                cal.set(Calendar.HOUR_OF_DAY, 0);
                cal.set(Calendar.MINUTE, 0);
                cal.set(Calendar.SECOND, 0);
                interval = UsageStatsManager.INTERVAL_DAILY;
                break;
        }
        long startTime = cal.getTimeInMillis();

        Map<String, UsageStats> statsMap = usm.queryAndAggregateUsageStats(startTime, endTime);
        if (statsMap == null) {
            statsMap = new HashMap<>();
        }

        // Get all installed launcher apps
        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launchables = pm.queryIntentActivities(intent, 0);

        Intent tvIntent = new Intent(Intent.ACTION_MAIN, null);
        tvIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> tvLaunchables = pm.queryIntentActivities(tvIntent, 0);

        Map<String, String> appLabels = new HashMap<>();
        for (ResolveInfo ri : launchables) {
            if (ri.activityInfo != null) {
                String pkg = ri.activityInfo.packageName;
                String label = ri.loadLabel(pm).toString();
                appLabels.put(pkg, label);
            }
        }
        for (ResolveInfo ri : tvLaunchables) {
            if (ri.activityInfo != null) {
                String pkg = ri.activityInfo.packageName;
                String label = ri.loadLabel(pm).toString();
                appLabels.put(pkg, label);
            }
        }

        List<AppStatItem> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : appLabels.entrySet()) {
            String pkg = entry.getKey();
            if (pkg.equals(context.getPackageName())) continue; // Skip self

            long timeMs = 0;
            if (statsMap.containsKey(pkg)) {
                UsageStats us = statsMap.get(pkg);
                if (us != null) {
                    timeMs = us.getTotalTimeInForeground();
                }
            }

            boolean isRestricted = prefs.isPackageRestricted(pkg);
            result.add(new AppStatItem(pkg, entry.getValue(), timeMs, isRestricted));
        }

        // Sort by usage time descending
        Collections.sort(result, (a, b) -> Long.compare(b.totalTimeMs, a.totalTimeMs));
        return result;
    }
}
