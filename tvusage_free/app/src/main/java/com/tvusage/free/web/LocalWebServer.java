package com.tvusage.free.web;

import android.content.Context;

import com.tvusage.free.util.AppPreferences;
import com.tvusage.free.util.UsageStatsHelper;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import fi.iki.elonen.NanoHTTPD;

/**
 * Local HTTP Web Server on port 8080 providing a responsive mobile-friendly dashboard.
 *
 * Features:
 * - Password authentication gate using D-Pad arrow PIN.
 * - Live Parent Mode switch ("Снять ограничения до выключения ТВ" / "Включить детский режим").
 * - Quick-add time buttons (+10 min, +20 min, +30 min, +1 hour).
 * - Shared timer budget and per-app control toggles.
 * - Day / Week / Month usage statistics.
 */
public class LocalWebServer extends NanoHTTPD {
    private final Context context;
    private final AppPreferences prefs;
    private final Set<String> activeAuthTokens = new HashSet<>();

    public LocalWebServer(Context context, int port) {
        super(port);
        this.context = context;
        this.prefs = new AppPreferences(context);
    }

    public void startServer() {
        try {
            start(SOCKET_READ_TIMEOUT, false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void stopServer() {
        stop();
    }

    private boolean isSessionAuthenticated(IHTTPSession session) {
        String cookieHeader = session.getHeaders().get("cookie");
        if (cookieHeader != null) {
            for (String cookie : cookieHeader.split(";")) {
                String[] pair = cookie.trim().split("=");
                if (pair.length == 2 && "tv_auth_token".equals(pair[0].trim())) {
                    if (activeAuthTokens.contains(pair[1].trim())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private String getEnteredPinClean(String raw) {
        if (raw == null) return "";
        return raw.toUpperCase().replaceAll("[^A-Z,]", "").trim();
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        // Handle Login Submission
        if (Method.POST.equals(method) && "/login".equals(uri)) {
            Map<String, String> files = new HashMap<>();
            try {
                session.parseBody(files);
                Map<String, String> params = session.getParms();
                String enteredPin = getEnteredPinClean(params.get("pin"));
                String parentPin = getEnteredPinClean(prefs.getParentPin());

                if (!enteredPin.isEmpty() && enteredPin.equals(parentPin)) {
                    String newToken = UUID.randomUUID().toString();
                    activeAuthTokens.add(newToken);

                    Response resp = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML,
                            "<html><head><meta http-equiv='refresh' content='0;url=/'></head><body>Redirecting...</body></html>");
                    resp.addHeader("Set-Cookie", "tv_auth_token=" + newToken + "; Path=/; HttpOnly");
                    return resp;
                } else {
                    return renderLoginPage("Неверный пароль. Попробуйте еще раз.");
                }
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage());
            }
        }

        // If not authenticated, force Login screen
        if (!isSessionAuthenticated(session)) {
            return renderLoginPage(null);
        }

        // Logout
        if ("/logout".equals(uri)) {
            Response resp = newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML,
                    "<html><head><meta http-equiv='refresh' content='0;url=/'></head><body>Logged out</body></html>");
            resp.addHeader("Set-Cookie", "tv_auth_token=deleted; Path=/; Expires=Thu, 01 Jan 1970 00:00:00 GMT");
            return resp;
        }

        // Toggle Parent Mode (unlimited until TV sleeps)
        if (uri.startsWith("/toggle_parent_mode")) {
            boolean current = prefs.isUnlockedUntilSleep();
            prefs.setUnlockedUntilSleep(!current);
            return newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML,
                    "<html><head><meta http-equiv='refresh' content='0;url=/'></head><body>Redirecting...</body></html>");
        }

        // Handle Quick Add Time Actions (/add_time?minutes=20)
        if (uri.startsWith("/add_time")) {
            String mStr = session.getParms().get("minutes");
            if (mStr != null) {
                try {
                    int addM = Integer.parseInt(mStr);
                    prefs.addBonusMinutes(addM);
                } catch (NumberFormatException ignored) {}
            }
            return newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML,
                    "<html><head><meta http-equiv='refresh' content='0;url=/'></head><body>Redirecting...</body></html>");
        }

        // Handle Reset Today Usage (/reset_today)
        if (uri.startsWith("/reset_today")) {
            prefs.resetTodayUsage();
            return newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML,
                    "<html><head><meta http-equiv='refresh' content='0;url=/'></head><body>Redirecting...</body></html>");
        }

        // Handle Set Remaining Minutes (/set_remaining?minutes=25)
        if (uri.startsWith("/set_remaining")) {
            String mStr = session.getParms().get("minutes");
            if (mStr != null) {
                try {
                    int remM = Integer.parseInt(mStr);
                    prefs.setRemainingMinutesToday(remM);
                    notifyTimeChanged("Остаток времени обновлен: " + remM + " мин.");
                } catch (NumberFormatException ignored) {}
            }
            return newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML,
                    "<html><head><meta http-equiv='refresh' content='0;url=/'></head><body>Redirecting...</body></html>");
        }

        // Handle Put TV to Sleep (/turn_off_tv)
        if (uri.startsWith("/turn_off_tv")) {
            com.tvusage.free.util.TvControlHelper.sleepTv(context);
            return newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML,
                    "<html><head><meta http-equiv='refresh' content='0;url=/'></head><body>Redirecting...</body></html>");
        }

        // Handle Form Save
        if (Method.POST.equals(method) && "/save".equals(uri)) {
            Map<String, String> files = new HashMap<>();
            try {
                session.parseBody(files);
                Map<String, String> params = session.getParms();

                if (params.containsKey("limit")) {
                    prefs.setDailyLimitMinutes(Integer.parseInt(params.get("limit")));
                }
                if (params.containsKey("pin") && !params.get("pin").trim().isEmpty()) {
                    prefs.setParentPin(getEnteredPinClean(params.get("pin")));
                }
                if (params.containsKey("tg_token")) {
                    prefs.setTgBotToken(params.get("tg_token").trim());
                }
                if (params.containsKey("tg_chat_id")) {
                    prefs.setTgChatId(params.get("tg_chat_id").trim());
                }

                // Handle package restriction checkboxes
                Set<String> newRestricted = new HashSet<>();
                for (String key : params.keySet()) {
                    if (key.startsWith("pkg_")) {
                        newRestricted.add(key.substring(4));
                    }
                }
                prefs.setRestrictedPackages(newRestricted);

                return newFixedLengthResponse(Response.Status.REDIRECT, MIME_HTML,
                        "<html><head><meta http-equiv='refresh' content='1;url=/'></head><body><h2 style='font-family:sans-serif;text-align:center;margin-top:50px'>✅ Настройки успешно сохранены!</h2></body></html>");
            } catch (Exception e) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage());
            }
        }

        // Render Dashboard
        String periodParam = session.getParms().get("period");
        UsageStatsHelper.Period period = UsageStatsHelper.Period.DAY;
        if ("week".equalsIgnoreCase(periodParam)) {
            period = UsageStatsHelper.Period.WEEK;
        } else if ("month".equalsIgnoreCase(periodParam)) {
            period = UsageStatsHelper.Period.MONTH;
        }

        List<UsageStatsHelper.AppStatItem> stats = UsageStatsHelper.getAppStats(context, period);

        int baseLimit = prefs.getDailyLimitMinutes();
        int bonusMin = prefs.getBonusMinutes();
        int totalLimit = prefs.getTotalEffectiveLimitMinutes();
        int usedSec = prefs.getTodayUsageSeconds();
        int usedMin = usedSec / 60;
        int remMin = prefs.getRemainingMinutes();
        boolean isParentMode = prefs.isUnlockedUntilSleep();
        String tgToken = prefs.getTgBotToken();
        String tgChatId = prefs.getTgChatId();

        StringBuilder appsHtml = new StringBuilder();
        long maxTime = 1;
        for (UsageStatsHelper.AppStatItem item : stats) {
            if (item.totalTimeMs > maxTime) maxTime = item.totalTimeMs;
        }

        for (UsageStatsHelper.AppStatItem item : stats) {
            int percent = (int) ((item.totalTimeMs * 100) / maxTime);
            String checked = item.isRestricted ? "checked" : "";
            String badge = item.isRestricted ? "<span style='color:#ff5252;font-size:12px;font-weight:bold'>[Контролируется]</span>" : "<span style='color:#81c784;font-size:12px'>[Без лимита]</span>";

            appsHtml.append("<div class='app-card'>")
                    .append("<div style='display:flex;justify-content:space-between;align-items:center'>")
                    .append("<div><b>").append(item.label).append("</b> ").append(badge)
                    .append("<div style='font-size:12px;color:#7a808c'>").append(item.packageName).append("</div></div>")
                    .append("<label class='switch'><input type='checkbox' name='pkg_").append(item.packageName).append("' ").append(checked).append("><span class='slider'></span></label>")
                    .append("</div>")
                    .append("<div style='display:flex;justify-content:space-between;font-size:13px;margin-top:8px'>")
                    .append("<span style='color:#a0a5b1'>Время в системе:</span>")
                    .append("<span style='color:#ffd54f;font-weight:bold'>").append(item.getFormattedTime()).append("</span>")
                    .append("</div>")
                    .append("<div class='bar-bg'><div class='bar-fill' style='width:").append(percent).append("%'></div></div>")
                    .append("</div>");
        }

        // Generate Today's Chronological Timeline
        StringBuilder timelineHtml = new StringBuilder();
        List<UsageStatsHelper.ActivityInterval> timeline = UsageStatsHelper.getTodayTimeline(context);
        if (timeline.isEmpty()) {
            timelineHtml.append("<div style='color:#7a808c;font-size:14px;padding:8px 0'>За сегодня активности не зафиксировано (телевизор был выключен).</div>");
        } else {
            // Show reverse order (most recent first)
            for (int i = timeline.size() - 1; i >= 0; i--) {
                UsageStatsHelper.ActivityInterval interval = timeline.get(i);
                String tag = interval.isRestricted ?
                        "<span style='color:#ff5252;font-size:11px;font-weight:bold;margin-left:6px'>[Контроль]</span>" :
                        "<span style='color:#81c784;font-size:11px;margin-left:6px'>[Свободно]</span>";

                timelineHtml.append("<div style='display:flex;justify-content:space-between;align-items:center;padding:10px 0;border-bottom:1px solid #222733'>")
                        .append("<div>")
                        .append("<div style='font-weight:bold;font-size:14px;color:#f0f2f5'>").append(interval.label).append(tag).append("</div>")
                        .append("<div style='font-size:12px;color:#00e676;margin-top:2px'>⏰ ").append(interval.getFormattedRange()).append("</div>")
                        .append("</div>")
                        .append("<div style='font-size:13px;font-weight:bold;color:#ffd54f;background:#202530;padding:4px 8px;border-radius:6px'>")
                        .append(interval.getDurationMinutes()).append(" мин</div>")
                        .append("</div>");
            }
        }

        String tabDayClass = (period == UsageStatsHelper.Period.DAY) ? "active" : "";
        String tabWeekClass = (period == UsageStatsHelper.Period.WEEK) ? "active" : "";
        String tabMonthClass = (period == UsageStatsHelper.Period.MONTH) ? "active" : "";

        String parentModeBadge = isParentMode ?
                "<div style='background:#1b382b;border:1px solid #00e676;color:#00e676;padding:12px;border-radius:10px;margin-bottom:14px;display:flex;justify-content:space-between;align-items:center'><div><b>👑 Режим родителя активен</b><div style='font-size:12px;color:#a3e635'>Ограничения сняты до выключения ТВ</div></div><a href='/toggle_parent_mode' style='background:#ff5252;color:#fff;padding:8px 12px;border-radius:6px;text-decoration:none;font-weight:bold;font-size:12px'>Включить контроль</a></div>" :
                "<div style='background:#231f24;border:1px solid #4a3b4e;color:#e2b6ea;padding:12px;border-radius:10px;margin-bottom:14px;display:flex;justify-content:space-between;align-items:center'><div><b>🛡️ Детский контроль активен</b><div style='font-size:12px;color:#9ca3af'>Таймер считает просмотр контролируемых программ</div></div><a href='/toggle_parent_mode' style='background:#00e676;color:#000;padding:8px 12px;border-radius:6px;text-decoration:none;font-weight:bold;font-size:12px'>Снять до сна ТВ</a></div>";

        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
                "<title>TVUsage Free — Контроль и Статистика</title>" +
                "<style>" +
                "body{font-family:-apple-system,sans-serif;background:#0d0f12;color:#f0f2f5;padding:16px;max-width:560px;margin:auto}" +
                ".card{background:#161920;border-radius:14px;padding:18px;margin-bottom:16px;border:1px solid #222733}" +
                "h2{margin-top:0;color:#00e676;font-size:20px}" +
                ".tabs{display:flex;gap:8px;margin-bottom:16px}" +
                ".tabs a{flex:1;text-align:center;padding:10px;background:#202530;color:#a0a5b1;text-decoration:none;border-radius:8px;font-size:14px;font-weight:bold}" +
                ".tabs a.active{background:#00e676;color:#000}" +
                ".app-card{background:#1d212b;border-radius:10px;padding:12px;margin-top:10px}" +
                ".bar-bg{background:#2a303e;height:6px;border-radius:3px;margin-top:8px;overflow:hidden}" +
                ".bar-fill{background:#00e676;height:100%}" +
                "label{display:block;margin-top:14px;font-size:13px;color:#a0a5b1}" +
                "input,select{width:100%;box-sizing:border-box;padding:12px;margin-top:6px;border-radius:8px;border:1px solid #2e333d;background:#202530;color:#fff;font-size:15px}" +
                ".btn{width:100%;background:#00e676;color:#000;padding:14px;border:none;border-radius:8px;font-size:16px;font-weight:bold;margin-top:18px;cursor:pointer}" +
                ".quick-btn{flex:1;background:#252b38;color:#00e676;padding:10px 4px;border:1px solid #363d4f;border-radius:8px;text-align:center;text-decoration:none;font-weight:bold;font-size:13px}" +
                ".quick-btn:hover{background:#30384a}" +
                ".btn-reset{background:#3b1e22;color:#ff5252;border:1px solid #5a262c;text-decoration:none;display:inline-block;padding:10px 16px;border-radius:8px;font-size:13px;font-weight:bold;margin-top:10px}" +
                ".switch{position:relative;display:inline-block;width:44px;height:24px}" +
                ".switch input{opacity:0;width:0;height:0}" +
                ".slider{position:absolute;cursor:pointer;top:0;left:0;right:0;bottom:0;background-color:#374151;transition:.3s;border-radius:24px}" +
                ".slider:before{position:absolute;content:'';height:18px;width:18px;left:3px;bottom:3px;background-color:white;transition:.3s;border-radius:50%}" +
                "input:checked+.slider{background-color:#ff5252}" +
                "input:checked+.slider:before{transform:translateX(20px)}" +
                ".top-bar{display:flex;justify-content:space-between;align-items:center;margin-bottom:12px}" +
                ".tg-user{background:#202530;padding:8px 12px;border-radius:8px;margin-top:6px;display:flex;justify-content:space-between;align-items:center;font-size:13px}" +
                ".tg-user-btn{background:#00e676;color:#000;border:none;padding:4px 8px;border-radius:4px;cursor:pointer;font-weight:bold;font-size:11px}" +
                "details.card{cursor:pointer;user-select:none}" +
                "details summary{outline:none;list-style:none;display:flex;justify-content:space-between;align-items:center}" +
                "details summary::-webkit-details-marker{display:none}" +
                "details summary::after{content:'▶';font-size:13px;color:#00e676;transition:transform .2s}" +
                "details[open] summary::after{content:'▼'}" +
                "details summary h2{margin:0;display:inline-block}" +
                "details .card-content{margin-top:14px;cursor:default}" +
                "</style></head><body>" +
                "<div class='top-bar'>" +
                "<div><span style='color:#8c93a4;font-size:13px'>🔒 Авторизовано</span> <span style='background:#222733;color:#00e676;font-size:11px;padding:2px 6px;border-radius:4px;font-weight:bold;margin-left:6px'>v1.1.0</span></div>" +
                "<a href='/logout' style='color:#ff5252;font-size:13px;text-decoration:none'>Выйти</a>" +
                "</div>" +
                parentModeBadge +
                "<div class='card'>" +
                "<h2>⏱️ Общий таймер контролируемых программ</h2>" +
                "<div style='font-size:15px;color:#e0e0e0;margin-bottom:6px'>Использовано сегодня: <b>" + usedMin + " мин (" + usedSec + " сек)</b></div>" +
                "<div style='font-size:16px;color:#ffd54f;margin-bottom:6px'>Осталось времени: <b>" + remMin + " мин</b> (из " + totalLimit + " мин)</div>" +
                "<form method='GET' action='/set_remaining' style='display:flex;gap:8px;align-items:center;margin-top:10px;margin-bottom:12px'>" +
                "<input type='number' name='minutes' value='" + remMin + "' min='0' max='1440' style='width:90px;padding:8px 10px;margin:0;font-size:14px;border-radius:6px'>" +
                "<button type='submit' class='quick-btn' style='padding:8px 14px;margin:0;cursor:pointer;border:none;background:#00e676;color:#000;font-weight:bold'>Изменить остаток</button>" +
                "</form>" +
                (bonusMin > 0 ? "<div style='font-size:13px;color:#00e676;margin-bottom:12px'>🎁 Бонусное время сегодня: +" + bonusMin + " мин</div>" : "") +
                "<div style='font-size:13px;color:#a0a5b1;margin-top:8px;margin-bottom:8px'>Быстро добавить время на сегодня:</div>" +
                "<div style='display:flex;gap:8px'>" +
                "<a href='/add_time?minutes=10' class='quick-btn'>+10 мин</a>" +
                "<a href='/add_time?minutes=20' class='quick-btn'>+20 мин</a>" +
                "<a href='/add_time?minutes=30' class='quick-btn'>+30 мин</a>" +
                "<a href='/add_time?minutes=60' class='quick-btn'>+1 час</a>" +
                "</div>" +
                "<div style='margin-top:12px;display:flex;gap:10px;flex-wrap:wrap'>" +
                "<a href='/reset_today' class='btn-reset' onclick=\"return confirm('Вы действительно хотите сбросить счетчик экранного времени за сегодня?');\">🔄 Сбросить счетчик сегодня</a>" +
                "<a href='/turn_off_tv' class='btn-reset' style='background:#1f2937;color:#93c5fd;border-color:#374151' onclick=\"return confirm('Выключить телевизор (перевести экран в спящий режим)?');\">🌙 Выключить ТВ (в сон)</a>" +
                "</div>" +
                "<div class='card'>" +
                "<h2>📊 Системная статистика ТВ</h2>" +
                "<div class='tabs'>" +
                "<a href='/?period=day' class='" + tabDayClass + "'>День</a>" +
                "<a href='/?period=week' class='" + tabWeekClass + "'>Неделя</a>" +
                "<a href='/?period=month' class='" + tabMonthClass + "'>Месяц</a>" +
                "</div>" +
                "</div>" +
                "<details class='card'>" +
                "<summary><h2>🕒 Хронология активности сегодня</h2></summary>" +
                "<div class='card-content'>" +
                "<div style='font-size:13px;color:#a0a5b1;margin-bottom:10px'>Периоды включения ТВ и запущенные программы:</div>" +
                timelineHtml +
                "</div>" +
                "</details>" +
                "<form method='POST' action='/save'>" +
                "<details class='card'>" +
                "<summary><h2>🎯 Выбор программ под ограничение</h2></summary>" +
                "<div class='card-content'>" +
                "<div style='font-size:13px;color:#a0a5b1;margin-bottom:12px'>Приложения, на которые распространяется общий таймер:</div>" +
                appsHtml +
                "</div>" +
                "</details>" +
                "<div class='card'>" +
                "<h2>⚙️ Настройки и пароль</h2>" +
                "<label>Базовый ежедневный лимит (минут):</label>" +
                "<input type='number' name='limit' value='" + baseLimit + "' min='0' placeholder='Например: 1, 10, 45, 60'>" +
                "<small style='color:#7a808c'>0 = без лимита. Можно ставить 1 минуту, 10 минут или 60 минут.</small>" +
                "<label>Сменить родительский пароль стрелок (пульта):</label>" +
                "<input type='password' name='pin' placeholder='Оставьте пустым, чтобы не менять'>" +
                "<small style='color:#7a808c'>Формат стрелок через запятую: UP,UP,DOWN,DOWN или LEFT,RIGHT,UP,DOWN</small>" +
                "<label>Telegram Bot Token (необязательно):</label>" +
                "<input type='text' name='tg_token' value='" + tgToken + "' placeholder='123456:ABC-DEF...'>" +
                "<label>Telegram Admin Chat ID (необязательно):</label>" +
                "<input type='text' id='tgChatIdInput' name='tg_chat_id' value='" + tgChatId + "' placeholder='Например: 12345678, 87654321'>" +
                "<small style='color:#7a808c'>Необязательно. Если пусто — бот ответит первому написавшему родителю. Можно указать несколько ID через запятую для обоих родителей.</small>" +
                renderTgConnectedUsersHtml(prefs.getTgConnectedUsersJson()) +
                "<button type='submit' class='btn'>Сохранить изменения</button>" +
                "</div></form>" +
                "<script>" +
                "function appendChatId(id) {" +
                "  var inp = document.getElementById('tgChatIdInput');" +
                "  if (!inp.value.trim()) { inp.value = id; } else if (inp.value.indexOf(id) === -1) { inp.value = inp.value.trim() + ', ' + id; }" +
                "}" +
                "</script>" +
                "</body></html>";

        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html);
    }

    private Response renderLoginPage(String errorMessage) {
        String errHtml = errorMessage != null ? "<div style='color:#ff5252;background:#351a1d;padding:10px;border-radius:8px;margin-bottom:14px;font-size:14px'>" + errorMessage + "</div>" : "";
        String html = "<!DOCTYPE html><html><head><meta charset='utf-8'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1'>" +
                "<title>Вход — TVUsage Free</title>" +
                "<style>" +
                "body{font-family:-apple-system,sans-serif;background:#0d0f12;color:#f0f2f5;display:flex;align-items:center;justify-content:center;height:90vh;margin:0;padding:16px}" +
                ".card{background:#161920;border-radius:14px;padding:24px;width:100%;max-width:380px;border:1px solid #222733;text-align:center}" +
                "h2{margin-top:0;color:#00e676}" +
                "input{width:100%;box-sizing:border-box;padding:14px;margin-top:12px;border-radius:8px;border:1px solid #2e333d;background:#202530;color:#fff;font-size:16px;text-align:center}" +
                ".btn{width:100%;background:#00e676;color:#000;padding:14px;border:none;border-radius:8px;font-size:16px;font-weight:bold;margin-top:18px;cursor:pointer}" +
                ".dpad-grid{display:grid;grid-template-columns:repeat(3, 1fr);gap:8px;margin-top:16px}" +
                ".dpad-btn{background:#232834;color:#fff;border:1px solid #363c4d;border-radius:8px;padding:12px;font-size:18px;cursor:pointer}" +
                ".dpad-btn:active{background:#00e676;color:#000}" +
                "</style></head><body>" +
                "<div class='card'>" +
                "<h2>🔒 Доступ к админке</h2>" +
                "<p style='color:#a0a5b1;font-size:14px'>Введите родительский код стрелок для входа</p>" +
                errHtml +
                "<form method='POST' action='/login'>" +
                "<input type='hidden' id='pinInput' name='pin' value=''>" +
                "<div id='pinDisplay' style='background:#202530;padding:14px;border-radius:8px;font-size:20px;letter-spacing:6px;min-height:24px;color:#00e676'>• • • • • •</div>" +
                "<div class='dpad-grid'>" +
                "<div></div><button type='button' class='dpad-btn' onclick='addKey(\"UP\")'>⬆️</button><div></div>" +
                "<button type='button' class='dpad-btn' onclick='addKey(\"LEFT\")'>⬅️</button>" +
                "<button type='button' class='dpad-btn' onclick='clearPin()'>🔄</button>" +
                "<button type='button' class='dpad-btn' onclick='addKey(\"RIGHT\")'>➡️</button>" +
                "<div></div><button type='button' class='dpad-btn' onclick='addKey(\"DOWN\")'>⬇️</button><div></div>" +
                "</div>" +
                "<button type='submit' class='btn'>Войти в админку</button>" +
                "</form>" +
                "<script>" +
                "let keys = [];" +
                "function addKey(k) {" +
                "  keys.push(k);" +
                "  document.getElementById('pinInput').value = keys.join(',');" +
                "  document.getElementById('pinDisplay').innerText = keys.map(x => x==='UP'?'⬆️':x==='DOWN'?'⬇️':x==='LEFT'?'⬅️':'➡️').join(' ');" +
                "}" +
                "function clearPin() {" +
                "  keys = [];" +
                "  document.getElementById('pinInput').value = '';" +
                "  document.getElementById('pinDisplay').innerText = '• • • • • •';" +
                "}" +
                "</script>" +
                "</div></body></html>";

        return newFixedLengthResponse(Response.Status.OK, MIME_HTML, html);
    }

    private String renderTgConnectedUsersHtml(String json) {
        StringBuilder sb = new StringBuilder();
        try {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            if (arr.length() == 0) {
                sb.append("<div style='background:#1a1d24;padding:10px;border-radius:8px;margin-top:10px;font-size:12px;color:#8c93a4'>")
                  .append("ℹ️ <i>Пока никто не писал боту в Telegram. Напишите боту <code>/start</code> в Telegram, и ваш аккаунт появится здесь для добавления в 1 клик.</i></div>");
                return sb.toString();
            }

            sb.append("<div style='margin-top:12px'>")
              .append("<div style='font-size:12px;color:#a0a5b1;font-weight:bold'>👥 Пользователи, написавшие боту (нажмите чтобы добавить):</div>");

            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                String id = obj.optString("id");
                String name = obj.optString("name");
                String user = obj.optString("username");
                String userDisplay = user.isEmpty() ? name : (name + " (@" + user + ")");

                sb.append("<div class='tg-user'>")
                  .append("<div><b>").append(userDisplay).append("</b><div style='font-size:11px;color:#7a808c'>ID: ").append(id).append("</div></div>")
                  .append("<button type='button' class='tg-user-btn' onclick=\"appendChatId('").append(id).append("')\">+ Добавить</button>")
                  .append("</div>");
            }
            sb.append("</div>");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return sb.toString();
    }

    private void notifyTimeChanged(String message) {
        try {
            android.content.Intent intent = new android.content.Intent(com.tvusage.free.ui.LockActivity.ACTION_TIME_CHANGED);
            intent.putExtra(com.tvusage.free.ui.LockActivity.EXTRA_GRANTED_MESSAGE, message);
            context.sendBroadcast(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
