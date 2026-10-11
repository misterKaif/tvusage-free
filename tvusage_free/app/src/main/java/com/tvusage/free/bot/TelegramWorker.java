package com.tvusage.free.bot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.tvusage.free.util.AppPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class TelegramWorker {
    private final Context context;
    private final AppPreferences prefs;
    private final OkHttpClient client;
    private final Handler mainHandler;
    private boolean isRunning = false;
    private long lastUpdateId = 0;

    public TelegramWorker(Context context) {
        this.context = context;
        this.prefs = new AppPreferences(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.client = new OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public void startPolling() {
        isRunning = true;
        new Thread(this::pollLoop).start();
    }

    public void stopPolling() {
        isRunning = false;
    }

    private void pollLoop() {
        while (isRunning) {
            String token = prefs.getTgBotToken();
            if (token == null || token.isEmpty()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {}
                continue;
            }

            try {
                String url = "https://api.telegram.org/bot" + token + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=25";
                Request req = new Request.Builder().url(url).build();
                Response resp = client.newCall(req).execute();
                if (resp.isSuccessful() && resp.body() != null) {
                    String body = resp.body().string();
                    JSONObject json = new JSONObject(body);
                    if (json.optBoolean("ok")) {
                        JSONArray results = json.optJSONArray("result");
                        if (results != null) {
                            for (int i = 0; i < results.length(); i++) {
                                JSONObject update = results.getJSONObject(i);
                                lastUpdateId = update.optLong("update_id");
                                handleTelegramUpdate(update);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    private boolean isAdmin(String chatId) {
        String configured = prefs.getTgChatId().trim();
        if (configured.isEmpty()) {
            // First user to message the bot becomes admin automatically
            prefs.setTgChatId(chatId);
            return true;
        }

        String[] ids = configured.split(",");
        for (String id : ids) {
            if (id.trim().equals(chatId)) {
                return true;
            }
        }
        return false;
    }

    private void handleTelegramUpdate(JSONObject update) {
        // 1. Check callback query (inline buttons)
        if (update.has("callback_query")) {
            JSONObject cb = update.optJSONObject("callback_query");
            if (cb != null) {
                String data = cb.optString("data");
                JSONObject from = cb.optJSONObject("from");
                String senderId = from != null ? String.valueOf(from.optLong("id")) : "";

                if (isAdmin(senderId)) {
                    if (data.startsWith("grant_")) {
                        int minutes = Integer.parseInt(data.replace("grant_", ""));
                        prefs.addGrantMinutes(minutes);
                        sendMessageWithMenu(senderId, "✅ Выдано +" + minutes + " мин просмотра!\nОсталось: " + prefs.getRemainingMinutes() + " мин.");
                    } else if ("reject".equals(data)) {
                        sendMessageWithMenu(senderId, "❌ Запрос на добавление времени отклонен.");
                    }
                }
            }
            return;
        }

        // 2. Check regular text messages
        if (update.has("message")) {
            JSONObject msg = update.optJSONObject("message");
            if (msg == null) return;
            JSONObject chat = msg.optJSONObject("chat");
            String chatId = chat != null ? String.valueOf(chat.optLong("id")) : "";
            String text = msg.optString("text").trim();

            if (!isAdmin(chatId)) {
                return; // Unauthorized user
            }

            if (text.startsWith("/start") || text.startsWith("/help") || text.equalsIgnoreCase("меню") || text.equals("/menu")) {
                sendMessageWithMenu(chatId, "📺 *TVUsage Free Bot*\nУправление родительским контролем на телевизоре.\n\nИспользуйте кнопки меню ниже для управления:");
            } else if (text.startsWith("/status") || text.contains("Статус") || text.contains("статистика")) {
                sendDetailedStatus(chatId);
            } else if (text.startsWith("/history") || text.contains("История") || text.contains("Хронология")) {
                sendTimelineHistory(chatId);
            } else if (text.startsWith("/grant")) {
                String[] parts = text.split(" ");
                int mins = parts.length > 1 ? Integer.parseInt(parts[1]) : 30;
                prefs.addGrantMinutes(mins);
                sendMessageWithMenu(chatId, "✅ Добавлено +" + mins + " мин просмотра!\nОсталось: " + prefs.getRemainingMinutes() + " мин.");
            } else if (text.equals("👑 Взрослый режим") || text.startsWith("/unlock")) {
                prefs.setUnlockedUntilSleep(true);
                prefs.setForceLocked(false);
                sendMessageWithMenu(chatId, "👑 *Режим родителя включен!*\nОграничения сняты до выключения/сна телевизора.");
            } else if (text.equals("🛡️ Детский режим")) {
                prefs.setUnlockedUntilSleep(false);
                prefs.setForceLocked(false);
                sendMessageWithMenu(chatId, "🛡️ *Детский контроль активен!*\nТаймер отслеживает просмотр контролируемых программ.\nОсталось: " + prefs.getRemainingMinutes() + " мин.");
            } else if (text.equals("🔒 Блокировать сейчас") || text.startsWith("/lock")) {
                prefs.setUnlockedUntilSleep(false);
                prefs.setForceLocked(true);
                sendMessageWithMenu(chatId, "🔒 *Телевизор немедленно заблокирован!*\nЭкран ввода пароля отобразится при просмотре контролируемых приложений.");
            } else if (text.equals("🌙 Выключить ТВ (в сон)") || text.startsWith("/off") || text.contains("Выключить")) {
                com.tvusage.free.util.TvControlHelper.sleepTv(context);
                sendMessageWithMenu(chatId, "🌙 *Телевизор переведен в спящий режим (Standby).*");
            } else if (text.equals("🔄 Сбросить счетчик сегодня") || text.startsWith("/reset")) {
                prefs.resetTodayUsage();
                sendMessageWithMenu(chatId, "🔄 *Счетчик за сегодня успешно сброшен на 0!*\nОсталось времени: " + prefs.getRemainingMinutes() + " мин.");
            } else if (text.equals("➕ Добавить время") || text.contains("Добавить время")) {
                sendAddTimeInlineButtons(chatId);
            } else {
                sendMessageWithMenu(chatId, "Неизвестная команда. Выберите действие из меню ниже:");
            }
        }
    }

    private void sendDetailedStatus(String chatId) {
        int usedSec = prefs.getTodayUsageSeconds();
        int usedMin = usedSec / 60;
        int remMin = prefs.getRemainingMinutes();
        int baseLimit = prefs.getDailyLimitMinutes();
        int bonusMin = prefs.getBonusMinutes();
        int totalLimit = prefs.getTotalEffectiveLimitMinutes();
        boolean unlocked = prefs.isUnlockedUntilSleep();
        boolean forceLocked = prefs.isForceLocked();

        boolean isScreenOn = com.tvusage.free.service.UsageMonitorService.isTvScreenOn();
        String activePkg = com.tvusage.free.service.UsageMonitorService.getActivePackage();
        String activeAppLabel = getAppLabel(activePkg);
        boolean isActiveRestricted = prefs.isPackageRestricted(activePkg);

        String screenStatus = isScreenOn ? "🟢 *ВКЛЮЧЕН* (работает)" : "🔴 *ВЫКЛЮЧЕН* (режим сна)";

        String modeText;
        if (unlocked) {
            modeText = "👑 *Взрослый режим* (без ограничений до сна ТВ)";
        } else if (forceLocked) {
            modeText = "🔒 *Принудительно заблокирован*";
        } else {
            modeText = "🛡️ *Детский контроль активен*";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Статус и аналитика ТВ*\n\n");
        sb.append("• *Экран ТВ:* ").append(screenStatus).append("\n");
        if (isScreenOn && !activePkg.isEmpty()) {
            sb.append("• *Сейчас на экране:* *").append(activeAppLabel).append("* ")
              .append(isActiveRestricted ? "🔒 _(контролируется)_" : "▫️ _(без ограничений)_").append("\n");
        }
        sb.append("• *Режим:* ").append(modeText).append("\n\n");

        sb.append("⏱️ *Контролируемое время:*\n");
        sb.append("• Использовано: *").append(usedMin).append(" мин* (").append(usedSec).append(" сек)\n");
        sb.append("• Базовый лимит: ").append(baseLimit > 0 ? (baseLimit + " мин") : "Без лимита").append("\n");
        if (bonusMin > 0) {
            sb.append("• Бонусное время сегодня: +").append(bonusMin).append(" мин\n");
        }
        sb.append("• Доступно осталось: *").append(remMin).append(" мин* (из ").append(totalLimit).append(" мин)\n");

        // Progress bar
        if (totalLimit > 0) {
            int percent = Math.min(100, (usedMin * 100) / totalLimit);
            int filledBars = (percent * 10) / 100;
            StringBuilder bar = new StringBuilder("[");
            for (int i = 0; i < 10; i++) {
                bar.append(i < filledBars ? "█" : "░");
            }
            bar.append("] ").append(percent).append("%");
            sb.append("• Прогресс: `").append(bar.toString()).append("`\n\n");
        } else {
            sb.append("\n");
        }

        String ip = com.tvusage.free.util.NetworkUtils.getLocalIpAddress();
        if (ip != null && !ip.isEmpty()) {
            sb.append("🌐 *Веб-панель:* `http://").append(ip).append(":8080`\n\n");
        }

        sb.append("📱 *Топ приложений за сегодня:*\n");
        try {
            java.util.List<com.tvusage.free.util.UsageStatsHelper.AppStatItem> stats =
                    com.tvusage.free.util.UsageStatsHelper.getAppStats(context, com.tvusage.free.util.UsageStatsHelper.Period.DAY);
            int count = 0;
            for (com.tvusage.free.util.UsageStatsHelper.AppStatItem item : stats) {
                if (item.totalTimeMs < 60000) continue; // Skip apps with less than 1 min
                String mark = item.isRestricted ? "🔒" : "▫️";
                sb.append(mark).append(" *").append(item.label).append("*: ")
                        .append(item.getFormattedTime()).append("\n");
                count++;
                if (count >= 5) break; // Top 5 apps
            }
            if (count == 0) {
                sb.append("_(нет активности за сегодня)_\n");
            }
        } catch (Exception e) {
            sb.append("_(не удалось получить системную статистику)_\n");
        }

        sendMessageWithMenu(chatId, sb.toString());
    }

    private String getAppLabel(String packageName) {
        if (packageName == null || packageName.isEmpty()) return "Рабочий стол";
        try {
            android.content.pm.PackageManager pm = context.getPackageManager();
            android.content.pm.ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (Exception e) {
            return packageName;
        }
    }

    private void sendAddTimeInlineButtons(String chatId) {
        String token = prefs.getTgBotToken();
        if (token.isEmpty()) return;

        String keyboardJson = "{\"inline_keyboard\":[[" +
                "{\"text\":\"+15 мин\",\"callback_data\":\"grant_15\"}," +
                "{\"text\":\"+30 мин\",\"callback_data\":\"grant_30\"}," +
                "{\"text\":\"+1 час\",\"callback_data\":\"grant_60\"}" +
                "]]}";

        RequestBody body = new FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", "⏱️ Выберите, сколько времени добавить:")
                .add("reply_markup", keyboardJson)
                .build();

        Request req = new Request.Builder()
                .url("https://api.telegram.org/bot" + token + "/sendMessage")
                .post(body)
                .build();

        client.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {}
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                response.close();
            }
        });
    }

    public static void sendAskTimeRequest(Context context) {
        AppPreferences prefs = new AppPreferences(context);
        String token = prefs.getTgBotToken();
        String configured = prefs.getTgChatId().trim();
        if (token.isEmpty() || configured.isEmpty()) return;

        String[] chatIds = configured.split(",");
        OkHttpClient client = new OkHttpClient();
        String keyboardJson = "{\"inline_keyboard\":[[" +
                "{\"text\":\"+15 мин\",\"callback_data\":\"grant_15\"}," +
                "{\"text\":\"+30 мин\",\"callback_data\":\"grant_30\"}," +
                "{\"text\":\"+1 час\",\"callback_data\":\"grant_60\"}," +
                "{\"text\":\"Отклонить\",\"callback_data\":\"reject\"}" +
                "]]}";

        for (String targetChatId : chatIds) {
            String cleanId = targetChatId.trim();
            if (cleanId.isEmpty()) continue;

            RequestBody body = new FormBody.Builder()
                    .add("chat_id", cleanId)
                    .add("text", "🚨 Ребенок просит дополнительное время на телевизоре!")
                    .add("reply_markup", keyboardJson)
                    .build();

            Request req = new Request.Builder()
                    .url("https://api.telegram.org/bot" + token + "/sendMessage")
                    .post(body)
                    .build();

            client.newCall(req).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {}
                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    response.close();
                }
            });
        }
    }

    private void sendMessageWithMenu(String chatId, String text) {
        String token = prefs.getTgBotToken();
        if (token.isEmpty()) return;

        // Persistent Reply Keyboard with convenient quick actions
        String menuJson = "{\"keyboard\":[" +
                "[{\"text\":\"📊 Статус и статистика\"},{\"text\":\"🕒 История за сегодня\"}]," +
                "[{\"text\":\"👑 Взрослый режим\"},{\"text\":\"🛡️ Детский режим\"}]," +
                "[{\"text\":\"🔒 Блокировать сейчас\"},{\"text\":\"🌙 Выключить ТВ (в сон)\"}]," +
                "[{\"text\":\"➕ Добавить время\"},{\"text\":\"🔄 Сбросить счетчик сегодня\"}]" +
                "],\"resize_keyboard\":true,\"is_persistent\":true}";

        RequestBody body = new FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", text)
                .add("parse_mode", "Markdown")
                .add("reply_markup", menuJson)
                .build();

        Request req = new Request.Builder()
                .url("https://api.telegram.org/bot" + token + "/sendMessage")
                .post(body)
                .build();

        client.newCall(req).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {}
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                response.close();
            }
        });
    }

    private void sendTimelineHistory(String chatId) {
        java.util.List<com.tvusage.free.util.UsageStatsHelper.ActivityInterval> timeline =
                com.tvusage.free.util.UsageStatsHelper.getTodayTimeline(context);

        if (timeline == null || timeline.isEmpty()) {
            sendMessageWithMenu(chatId, "🕒 *История за сегодня:*\nЗа сегодня включений ТВ не зафиксировано (телевизор был выключен).");
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("🕒 *Хронология активности ТВ за сегодня:*\n\n");

        long totalWatchedMin = 0;
        // Show reverse order: latest sessions first
        for (int i = timeline.size() - 1; i >= 0; i--) {
            com.tvusage.free.util.UsageStatsHelper.ActivityInterval interval = timeline.get(i);
            String tag = interval.isRestricted ? "🔒" : "▫️";
            sb.append("• `").append(interval.getFormattedRange()).append("` (")
              .append(interval.getDurationMinutes()).append(" мин) — ")
              .append(tag).append(" *").append(interval.label).append("*\n");
            totalWatchedMin += interval.getDurationMinutes();
        }

        sb.append("\n📺 *Всего сессий включения:* ").append(timeline.size()).append("\n");
        sb.append("⏳ *Суммарное время работы:* ").append(totalWatchedMin / 60 > 0 ? (totalWatchedMin / 60 + " ч ") : "")
          .append(totalWatchedMin % 60).append(" мин");

        sendMessageWithMenu(chatId, sb.toString());
    }
}
