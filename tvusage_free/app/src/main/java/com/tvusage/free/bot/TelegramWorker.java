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

    private void handleTelegramUpdate(JSONObject update) {
        String adminChatId = prefs.getTgChatId();

        // 1. Check callback query (inline buttons)
        if (update.has("callback_query")) {
            JSONObject cb = update.optJSONObject("callback_query");
            if (cb != null) {
                String data = cb.optString("data");
                JSONObject from = cb.optJSONObject("from");
                String senderId = from != null ? String.valueOf(from.optLong("id")) : "";

                if (senderId.equals(adminChatId)) {
                    if (data.startsWith("grant_")) {
                        int minutes = Integer.parseInt(data.replace("grant_", ""));
                        prefs.addGrantMinutes(minutes);
                        sendMessage(adminChatId, "✅ Выдано +" + minutes + " мин просмотра!");
                    } else if ("reject".equals(data)) {
                        sendMessage(adminChatId, "❌ Запрос отклонен.");
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
            String text = msg.optString("text");

            if (!chatId.equals(adminChatId) && !adminChatId.isEmpty()) {
                return; // Unauthorized user
            }

            if (text.startsWith("/start") || text.startsWith("/help")) {
                sendMessage(chatId, "📺 *TVUsage Free Bot*\n\n" +
                        "/status — статус ТВ и оставшееся время\n" +
                        "/grant <мин> — выдать N минут ребенку\n" +
                        "/unlock — разблокировать текущую сессию\n" +
                        "/lock — немедленно заблокировать");
            } else if (text.startsWith("/status")) {
                int remMin = prefs.getRemainingMinutes();
                int usedMin = prefs.getTodayUsageSeconds() / 60;
                boolean unlocked = prefs.isUnlockedUntilSleep();
                sendMessage(chatId, "📊 *Статус ТВ:*\n" +
                        "• Использовано: " + usedMin + " мин\n" +
                        "• Осталось: " + remMin + " мин\n" +
                        "• Режим взрослого: " + (unlocked ? "ВКЛ (без лимитов)" : "ВЫКЛ"));
            } else if (text.startsWith("/grant")) {
                String[] parts = text.split(" ");
                int mins = parts.length > 1 ? Integer.parseInt(parts[1]) : 30;
                prefs.addGrantMinutes(mins);
                sendMessage(chatId, "✅ Добавлено +" + mins + " мин просмотра!");
            } else if (text.startsWith("/unlock")) {
                prefs.setUnlockedUntilSleep(true);
                sendMessage(chatId, "🔓 Телевизор разблокирован для взрослого до выключения экрана.");
            } else if (text.startsWith("/lock")) {
                prefs.setUnlockedUntilSleep(false);
                prefs.addUsageSeconds(999999); // force expire
                sendMessage(chatId, "🔒 Телевизор заблокирован!");
            }
        }
    }

    public static void sendAskTimeRequest(Context context) {
        AppPreferences prefs = new AppPreferences(context);
        String token = prefs.getTgBotToken();
        String chatId = prefs.getTgChatId();
        if (token.isEmpty() || chatId.isEmpty()) return;

        OkHttpClient client = new OkHttpClient();
        String keyboardJson = "{\"inline_keyboard\":[[" +
                "{\"text\":\"+15 мин\",\"callback_data\":\"grant_15\"}," +
                "{\"text\":\"+30 мин\",\"callback_data\":\"grant_30\"}," +
                "{\"text\":\"+1 час\",\"callback_data\":\"grant_60\"}," +
                "{\"text\":\"Отклонить\",\"callback_data\":\"reject\"}" +
                "]]}";

        RequestBody body = new FormBody.Builder()
                .add("chat_id", chatId)
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

    private void sendMessage(String chatId, String text) {
        String token = prefs.getTgBotToken();
        if (token.isEmpty()) return;

        RequestBody body = new FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", text)
                .add("parse_mode", "Markdown")
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
