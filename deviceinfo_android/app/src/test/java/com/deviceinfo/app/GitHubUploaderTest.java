package com.deviceinfo.app;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** Проверка запроса к GitHub на локальном HTTP-сервере. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class GitHubUploaderTest {
    ServerSocket srv;

    @After public void stop() throws Exception {
        if (srv != null) srv.close();
        GitHubUploader.apiBase = "https://api.github.com";
    }

    /** Мини-HTTP-сервер на один запрос. got = {метод, путь, Authorization, тело, Accept}. */
    private void start(int code, String resp, AtomicReference<String[]> got) throws Exception {
        srv = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            try (Socket so = srv.accept()) {
                java.io.InputStream in = so.getInputStream();
                StringBuilder head = new StringBuilder();
                int c, n = 0;
                while ((c = in.read()) >= 0) {
                    head.append((char) c);
                    if (head.toString().endsWith("\r\n\r\n")) break;
                }
                String[] lines = head.toString().split("\r\n");
                String[] req = lines[0].split(" ");
                String auth = null, accept = null;
                int len = 0;
                for (String l : lines) {
                    String lo = l.toLowerCase();
                    if (lo.startsWith("authorization:")) auth = l.substring(14).trim();
                    if (lo.startsWith("accept:")) accept = l.substring(7).trim();
                    if (lo.startsWith("content-length:")) len = Integer.parseInt(l.substring(15).trim());
                }
                byte[] body = new byte[len];
                int off = 0;
                while (off < len) { int r = in.read(body, off, len - off); if (r < 0) break; off += r; }
                got.set(new String[]{req[0], req[1], auth, new String(body, StandardCharsets.UTF_8), accept});
                byte[] r = resp.getBytes(StandardCharsets.UTF_8);
                OutputStream os = so.getOutputStream();
                os.write(("HTTP/1.1 " + code + " X\r\nContent-Type: application/json\r\nContent-Length: " + r.length
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(r);
                os.flush();
            } catch (Exception ignored) { }
        });
        t.setDaemon(true);
        t.start();
        GitHubUploader.apiBase = "http://127.0.0.1:" + srv.getLocalPort();
    }

    @Test public void uploadSuccess() throws Exception {
        AtomicReference<String[]> got = new AtomicReference<>();
        start(201, "{\"content\":{\"html_url\":\"https://github.com/x/y/blob/b/p.txt\"}}", got);
        GitHubUploader.Result r = GitHubUploader.upload(" github_pat_ABC ", "Ne-collab228/Test2",
                "device-info-reports", "device-reports/NIC-LX1/2026_v1.0.6.txt", "Отчёт: тест", "msg");
        assertTrue(r.message, r.ok);
        assertEquals("https://github.com/x/y/blob/b/p.txt", r.message);
        String[] g = got.get();
        assertEquals("PUT", g[0]);
        assertEquals("/repos/Ne-collab228/Test2/contents/device-reports/NIC-LX1/2026_v1.0.6.txt", g[1]);
        assertEquals("Bearer github_pat_ABC", g[2]);
        assertEquals("application/vnd.github+json", g[4]);
        JSONObject b = new JSONObject(g[3]);
        assertEquals("device-info-reports", b.getString("branch"));
        assertEquals("msg", b.getString("message"));
        String decoded = new String(java.util.Base64.getDecoder().decode(b.getString("content")), StandardCharsets.UTF_8);
        assertEquals("Отчёт: тест", decoded);
    }

    @Test public void uploadForbidden() throws Exception {
        AtomicReference<String[]> got = new AtomicReference<>();
        start(403, "{\"message\":\"Resource not accessible by personal access token\"}", got);
        GitHubUploader.Result r = GitHubUploader.upload("t", "Ne-collab228/Test2", "b", "p.txt", "x", "m");
        assertFalse(r.ok);
        assertTrue(r.message, r.message.contains("403") && r.message.contains("Resource not accessible"));
    }

    @Test public void uploadNotFound() throws Exception {
        AtomicReference<String[]> got = new AtomicReference<>();
        start(404, "{\"message\":\"Not Found\"}", got);
        GitHubUploader.Result r = GitHubUploader.upload("t", "Ne-collab228/Test2", "b", "p.txt", "x", "m");
        assertFalse(r.ok);
        assertTrue(r.message, r.message.contains("404"));
    }

    @Test public void noNetwork() {
        GitHubUploader.apiBase = "http://127.0.0.1:1";
        GitHubUploader.Result r = GitHubUploader.upload("t", "a/b", "b", "p.txt", "x", "m");
        assertFalse(r.ok);
        assertTrue(r.message, r.message.startsWith("нет связи"));
    }

    /** Весь путь: экран → сбор отчёта → отправка (то, что раньше «ничего не делало»). */
    @Test public void activityUploadEndToEnd() throws Exception {
        AtomicReference<String[]> got = new AtomicReference<>();
        start(201, "{\"content\":{\"html_url\":\"https://example/x.txt\"}}", got);
        android.content.Context ctx = org.robolectric.RuntimeEnvironment.getApplication();
        ctx.getSharedPreferences("github", 0).edit().putString("token", "github_pat_TEST")
                .putLong("uploaded_version", 999999).apply();
        org.robolectric.android.controller.ActivityController<MainActivity> c =
                org.robolectric.Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity a = c.get();
        a.uploadReport(false);
        long end = System.currentTimeMillis() + 60000;
        while (got.get() == null && System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            Thread.sleep(200);
        }
        assertNotNull("запрос не дошёл до сервера", got.get());
        String[] g = got.get();
        assertEquals("PUT", g[0]);
        assertTrue(g[1], g[1].startsWith("/repos/Ne-collab228/Test2/contents/device-reports/"));
        JSONObject b = new JSONObject(g[3]);
        assertEquals("device-info-reports", b.getString("branch"));
        String rep = new String(java.util.Base64.getDecoder().decode(b.getString("content")), StandardCharsets.UTF_8);
        assertTrue(rep, rep.contains("===== Процессор =====") && rep.contains("===== Датчики ====="));
        // дождаться обработки ответа и проверить, что версия отмечена как загруженная
        end = System.currentTimeMillis() + 10000;
        while (ctx.getSharedPreferences("github", 0).getLong("uploaded_version", -1) == 999999
                && System.currentTimeMillis() < end) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            Thread.sleep(100);
        }
        assertNotEquals(999999, ctx.getSharedPreferences("github", 0).getLong("uploaded_version", -1));
        c.pause().stop().destroy();
    }
}
