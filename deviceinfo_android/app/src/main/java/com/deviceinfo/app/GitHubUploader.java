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
        return upload(token, repo, branch, path, content.getBytes(StandardCharsets.UTF_8), commitMessage);
    }

    /** PUT /repos/{repo}/contents/{path} с произвольными байтами. Выполнять в фоновом потоке. */
    public static Result upload(String token, String repo, String branch, String path,
                                byte[] content, String commitMessage) {
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
            body.put("content", Base64.encodeToString(content, Base64.NO_WRAP));
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

    /** Файл в папке репозитория. */
    public static final class Entry {
        public final String name, path, sha;
        Entry(String name, String path, String sha) { this.name = name; this.path = path; this.sha = sha; }
    }

    private static HttpURLConnection open(String token, String urlStr, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("Authorization", "Bearer " + token.trim());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "DeviceInfo-App");
        return c;
    }

    /** GET /repos/{repo}/contents/{dir}?ref={branch}: только файлы. 404 - пустой список, ошибка - null. */
    public static java.util.List<Entry> list(String token, String repo, String branch, String dir) {
        HttpURLConnection c = null;
        try {
            String u = apiBase + "/repos/" + repo + "/contents/" + dir.replace(" ", "%20");
            if (branch != null && !branch.isEmpty()) u += "?ref=" + java.net.URLEncoder.encode(branch, "UTF-8");
            c = open(token, u, "GET");
            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 404) return new java.util.ArrayList<>();
            if (code != 200) return null;
            org.json.JSONArray arr = new org.json.JSONArray(resp);
            java.util.List<Entry> out = new java.util.ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!"file".equals(o.optString("type"))) continue;
                out.add(new Entry(o.optString("name"), o.optString("path"), o.optString("sha")));
            }
            return out;
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** DELETE /repos/{repo}/contents/{path}. Выполнять в фоновом потоке. */
    public static Result delete(String token, String repo, String branch, String path,
                                String sha, String commitMessage) {
        HttpURLConnection c = null;
        try {
            c = open(token, apiBase + "/repos/" + repo + "/contents/" + path.replace(" ", "%20"), "DELETE");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            JSONObject body = new JSONObject();
            body.put("message", commitMessage);
            body.put("sha", sha);
            if (branch != null && !branch.isEmpty()) body.put("branch", branch);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code == 200) return new Result(true, path);
            String msg = resp;
            try { msg = new JSONObject(resp).optString("message", resp); } catch (Throwable ignored) { }
            if (code == 401) msg = "неверный или просроченный токен (401)";
            else if (code == 403) msg = "нет прав на запись в репозиторий (403): " + msg;
            else if (code == 404) msg = "файл, репозиторий или ветка не найдены, либо у токена нет доступа (404)";
            else if (code == 409 || code == 422) msg = "GitHub отклонил запрос (" + code + "): " + msg;
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
