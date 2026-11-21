package com.deviceinfo.app;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Загрузка отчёта файлом в репозиторий GitHub через REST API (contents).
 * Токен вводит пользователь в приложении; в APK он не хранится.
 */
public final class GitHubUploader {
    private GitHubUploader() {}

    /** Адрес API (подменяется в тестах). */
    static String apiBase = "https://api.github.com";

    public static final class Result {
        public final boolean ok;
        public final String message;
        Result(boolean ok, String message) { this.ok = ok; this.message = message; }
    }

    /** PUT /repos/{repo}/contents/{path}. Выполнять в фоновом потоке. */
    public static Result upload(String token, String repo, String branch, String path,
                                String content, String commitMessage) {
        HttpURLConnection c = null;
        try {
            String encPath = path.replace(" ", "%20");
            URL url = new URL(apiBase + "/repos/" + repo + "/contents/" + encPath);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestMethod("PUT");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + token.trim());
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            c.setRequestProperty("User-Agent", "DeviceInfo-App");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JSONObject body = new JSONObject();
            body.put("message", commitMessage);
            body.put("content", Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            if (branch != null && !branch.isEmpty()) body.put("branch", branch);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 200 || code == 201) {
                String link = null;
                try { link = new JSONObject(resp).getJSONObject("content").optString("html_url", null); }
                catch (Throwable ignored) { }
                return new Result(true, link != null ? link : path);
            }
            String msg = resp;
            try { msg = new JSONObject(resp).optString("message", resp); } catch (Throwable ignored) { }
            if (code == 401) msg = "неверный или просроченный токен (401)";
            else if (code == 403) msg = "нет прав на запись в репозиторий (403): " + msg;
            else if (code == 404) msg = "репозиторий или ветка не найдены, либо у токена нет доступа (404)";
            else if (code == 422) msg = "GitHub отклонил запрос (422): " + msg;
            else msg = "HTTP " + code + ": " + msg;
            return new Result(false, msg);
        } catch (Throwable e) {
            return new Result(false, "нет связи с GitHub: " + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        try (InputStream is = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            return bo.toString("UTF-8");
        }
    }

    /** Убирает из отчёта сетевые адреса перед загрузкой в публичный репозиторий. */
    public static String redact(String report) {
        StringBuilder sb = new StringBuilder();
        boolean inIp = false;
        for (String line : report.split("\n", -1)) {
            if (line.startsWith("IP-адреса:")) { sb.append("IP-адреса: [скрыто]\n"); inIp = true; continue; }
            if (inIp) {
                if (line.startsWith("wlan") || line.startsWith("rmnet") || line.startsWith("ccmni")
                        || line.startsWith("DNS:") || line.matches("^[a-z0-9_]+\\d*: [0-9a-fA-F:.]+$")) continue;
                inIp = false;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }
}
