package com.deviceinfo.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final int BG = 0xFF121417, HEADER = 0xFF1A1D22, SECTION = 0xFF2A2F37,
            ROW1 = 0xFF1D2127, ROW2 = 0xFF23282F, ACCENT = 0xFFE53935, TEXT = 0xFFECEFF1,
            MUTED = 0xFF9AA5B1, OKC = 0xFF43A047, WARNC = 0xFFFB8C00, INFOC = 0xFF1E88E5,
            BTN = 0xFF2F353E, BAR_BG = 0xFF33383F;
    static final long PERIOD = 1500;

    private final Handler ui = new Handler(Looper.getMainLooper());
    /** Сборка видимой вкладки. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Фоновые замеры (tick) и предварительная подготовка остальных вкладок. */
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    /** Отчёт и загрузка в GitHub — отдельный поток, чтобы не стоять в очереди за замерами. */
    private final ExecutorService reporter = Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean ticking = new java.util.concurrent.atomic.AtomicBoolean();
    /** Сообщение в нижней строке, которое не затирается автообновлением до указанного времени. */
    private String statusMsg;
    private long statusUntil;
    private final List<Page> pages = Pages.create();
    private final List<Button> tabButtons = new ArrayList<>();
    /** Последний собранный результат каждой вкладки — показывается мгновенно при переключении. */
    private final Map<Integer, Section> cache = new HashMap<>();
    private LinearLayout content;
    private ScrollView scroll;
    private TextView status;
    private int current = 0;
    private boolean resumed;
    private volatile boolean busy;

    /** Текущая отрисовка: элементы и их View (для обновления «на месте»). */
    private String shownSignature;
    private final List<Object> holders = new ArrayList<>();

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (pages.get(current).live()) refresh();
            final Context app = getApplicationContext();
            // Не копим замеры: если прошлый ещё идёт, пропускаем этот.
            if (ticking.compareAndSet(false, true)) {
                background.execute(() -> {
                    try {
                        for (Page p : pages) {
                            if (p instanceof BatteryPage && BatteryWearService.running) continue; // замер ведёт служба
                            try { synchronized (p) { p.tick(app); } } catch (Throwable ignored) { }
                        }
                    } finally { ticking.set(false); }
                });
            }
            ui.postDelayed(this, PERIOD);
        }
    };

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(HEADER);
        // Android 15 рисует приложение под строкой состояния и вырезом камеры:
        // отодвигаем содержимое на размер системных панелей.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, btm;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets in = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = in.left; t = in.top; r = in.right; btm = in.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); btm = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, btm);
            return Build.VERSION.SDK_INT >= 30 ? WindowInsets.CONSUMED : insets.consumeSystemWindowInsets();
        });

        // Шапка
        LinearLayout head = new LinearLayout(this);
        head.setBackgroundColor(HEADER);
        head.setPadding(dp(12), dp(8), dp(8), dp(8));
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        String market = Util.firstNonEmpty(Util.prop("ro.product.marketname"), Util.prop("ro.config.marketing_name"));
        title.setText(market != null ? market + "  ·  " + Build.MODEL : Build.MANUFACTURER.toUpperCase() + " " + Build.MODEL);
        title.setTextColor(TEXT);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button report = makeButton("Отчёт");
        report.setOnClickListener(v -> reportMenu());
        head.addView(report, new LinearLayout.LayoutParams(-2, dp(38)));
        root.addView(head);
        View line = new View(this);
        line.setBackgroundColor(ACCENT);
        root.addView(line, new LinearLayout.LayoutParams(-1, dp(2)));

        // Вкладки
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(HEADER);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < pages.size(); i++) {
            final int idx = i;
            Button t = makeButton(pages.get(i).title());
            t.setOnClickListener(v -> switchTab(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(40));
            lp.setMargins(dp(2), 0, dp(2), 0);
            tabs.addView(t, lp);
            tabButtons.add(t);
        }
        hs.addView(tabs);
        root.addView(hs);

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, dp(16));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        status = new TextView(this);
        status.setTextColor(MUTED);
        status.setTextSize(12);
        status.setBackgroundColor(HEADER);
        status.setPadding(dp(10), dp(4), dp(10), dp(4));
        root.addView(status);

        setContentView(root);
        switchTab(0);
        prewarm();
        ui.postDelayed(this::autoUploadIfUpdated, 6000);
    }

    /** Заранее собирает все вкладки в фоне, чтобы переключение было мгновенным. */
    void prewarm() {
        final Context app = getApplicationContext();
        for (int i = 0; i < pages.size(); i++) {
            final int idx = i;
            background.execute(() -> {
                if (cache.containsKey(idx)) return;
                Page pg = pages.get(idx);
                Section s = buildSafe(pg, app);
                // Невидимая вкладка не должна держать ресурсы (например, слушатели датчиков).
                if (idx != current) {
                    try { synchronized (pg) { pg.onHide(app); } } catch (Throwable ignored) { }
                }
                ui.post(() -> { if (!cache.containsKey(idx)) cache.put(idx, s); });
            });
        }
    }

    Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(TEXT);
        b.setTextSize(14);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BTN);
        bg.setCornerRadius(dp(4));
        b.setBackground(bg);
        return b;
    }

    void switchTab(int idx) {
        if (idx != current) {
            final Page old = pages.get(current);
            final Context app = getApplicationContext();
            worker.execute(() -> { try { synchronized (old) { old.onHide(app); } } catch (Throwable ignored) { } });
        }
        current = idx;
        for (int i = 0; i < tabButtons.size(); i++) {
            GradientDrawable bg = (GradientDrawable) tabButtons.get(i).getBackground();
            bg.setColor(i == idx ? ACCENT : BTN);
        }
        shownSignature = null;
        Section cached = cache.get(idx);
        if (cached != null) {
            render(cached, false);
        } else {
            content.removeAllViews();
            holders.clear();
            TextView wait = text("Загрузка…", MUTED, 14);
            wait.setPadding(dp(12), dp(16), dp(12), dp(16));
            content.addView(wait);
        }
        scroll.scrollTo(0, 0);
        maybeAskPermissions(pages.get(idx));
        refresh();
    }

    /** Шагомеру на Android 10+ нужно разрешение «Физическая активность». */
    void maybeAskPermissions(Page p) {
        if (!(p instanceof SensorsPage) || Build.VERSION.SDK_INT < 29) return;
        if (checkSelfPermission("android.permission.ACTIVITY_RECOGNITION") != PackageManager.PERMISSION_GRANTED) {
            try { requestPermissions(new String[]{"android.permission.ACTIVITY_RECOGNITION"}, 1); } catch (Throwable ignored) { }
        }
    }

    /** Нажатие кнопки-действия (UI-поток). */
    void onAction(String id) {
        if ("meter_start".equals(id) || "precise_start".equals(id)) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2); } catch (Throwable ignored) { }
            }
            try { startForegroundService(new Intent(this, BatteryWearService.class)
                    .putExtra(BatteryWearService.EXTRA_PRECISE, "precise_start".equals(id))); }
            catch (Throwable e) { toastStatus("Не удалось запустить замер: " + e.getMessage()); }
        } else if ("precise_finish".equals(id)) {
            try {
                startService(new Intent(this, BatteryWearService.class).setAction(BatteryWearService.ACTION_FINISH));
            } catch (Throwable ignored) { }
        } else if ("meter_stop".equals(id)) {
            try {
                startService(new Intent(this, BatteryWearService.class).setAction(BatteryWearService.ACTION_STOP));
            } catch (Throwable ignored) { }
        } else if ("battery_opt".equals(id)) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + getPackageName())));
            } catch (Throwable e) {
                try { startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
                catch (Throwable ignored) { }
            }
        }
        refresh();
        ui.postDelayed(this::refresh, 600);
    }

    private void toastStatus(String m) {
        statusMsg = m; statusUntil = System.currentTimeMillis() + 5000;
        if (status != null) status.setText(m);
    }

    void refresh() {
        if (busy) return;
        busy = true;
        final int idx = current;
        final Page page = pages.get(idx);
        final Context app = getApplicationContext();
        worker.execute(() -> {
            Section s = buildSafe(page, app);
            ui.post(() -> {
                busy = false;
                cache.put(idx, s);
                if (idx == current && !isFinishing()) render(s, true);
            });
        });
    }

    static Section buildSafe(Page page, Context ctx) {
        Section s = new Section();
        // Вкладку могут собирать одновременно видимый поток и фоновая подготовка:
        // сборка одной вкладки идёт строго по очереди (у вкладок есть состояние между замерами).
        try {
            synchronized (page) { page.build(ctx, s); }
        } catch (Throwable e) {
            s.header("Ошибка");
            s.row("Исключение", e.getClass().getSimpleName() + ": " + e.getMessage(), Section.CRIT);
        }
        return s;
    }

    int color(int c) {
        switch (c) {
            case Section.OK: return OKC;
            case Section.WARN: return WARNC;
            case Section.CRIT: return ACCENT;
            case Section.MUTED: return MUTED;
            case Section.INFO: return INFOC;
            default: return TEXT;
        }
    }

    /** Структура вкладки: если она не изменилась, значения обновляются в уже созданных View. */
    static String signature(Section s) {
        StringBuilder sb = new StringBuilder();
        for (Section.Item it : s.items) {
            sb.append(it.type).append('|');
            if (it.type == 0 || it.type == 1 || it.type == 2 || it.type == 5) sb.append(it.key);
            if (it.type == 4) sb.append(it.rows.size()).append('x').append(it.headers.length);
            sb.append('\n');
        }
        return sb.toString();
    }

    // Держатели View для обновления на месте
    static final class RowH { TextView value; }
    static final class BarH { TextView value; ProgressBar pb; GradientDrawable fill; }
    static final class NoteH { TextView text; }
    static final class ActionH { Button button; }
    static final class TableH { List<TextView[]> cells = new ArrayList<>(); }

    void render(Section s, boolean live) {
        String sig = signature(s);
        if (live && sig.equals(shownSignature) && holders.size() == s.items.size()) {
            update(s);
        } else {
            rebuild(s);
            shownSignature = sig;
        }
        if (statusMsg != null && System.currentTimeMillis() < statusUntil) { status.setText(statusMsg); return; }
        status.setText("Обновлено: " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date()) + (pages.get(current).live() ? "  •  авто 1.5 с" : ""));
    }

    void update(Section s) {
        for (int i = 0; i < s.items.size(); i++) {
            Section.Item it = s.items.get(i);
            Object h = holders.get(i);
            switch (it.type) {
                case 1: {
                    RowH r = (RowH) h;
                    setText(r.value, it.value);
                    r.value.setTextColor(color(it.color));
                    break;
                }
                case 2: {
                    BarH bh = (BarH) h;
                    setText(bh.value, it.value);
                    bh.pb.setProgress((int) (it.pct * 10));
                    bh.fill.setColor(it.color == Section.NORMAL ? INFOC : color(it.color));
                    break;
                }
                case 3: {
                    NoteH n = (NoteH) h;
                    setText(n.text, it.value);
                    n.text.setTextColor(color(it.color));
                    break;
                }
                case 5: {
                    setText(((ActionH) h).button, it.value);
                    break;
                }
                case 4: {
                    TableH th = (TableH) h;
                    for (int r = 0; r < it.rows.size() && r < th.cells.size(); r++) {
                        String[] row = it.rows.get(r);
                        TextView[] tv = th.cells.get(r);
                        for (int c = 0; c < row.length && c < tv.length; c++)
                            setText(tv[c], row[c] == null ? "—" : row[c]);
                    }
                    break;
                }
            }
        }
    }

    static void setText(TextView t, String s) {
        if (!s.contentEquals(t.getText())) t.setText(s);
    }

    void rebuild(Section s) {
        int y = scroll.getScrollY();
        content.removeAllViews();
        holders.clear();
        boolean odd = false;
        for (Section.Item it : s.items) {
            switch (it.type) {
                case 0: {
                    View gap = new View(this);
                    content.addView(gap, new LinearLayout.LayoutParams(-1, dp(6)));
                    TextView h = text(it.key, TEXT, 15);
                    h.setTypeface(Typeface.DEFAULT_BOLD);
                    h.setBackgroundColor(SECTION);
                    h.setPadding(dp(12), dp(8), dp(12), dp(8));
                    content.addView(h);
                    holders.add(null);
                    odd = false;
                    break;
                }
                case 1: {
                    odd = !odd;
                    LinearLayout r = new LinearLayout(this);
                    r.setBackgroundColor(odd ? ROW1 : ROW2);
                    r.setPadding(dp(12), dp(7), dp(12), dp(7));
                    r.addView(text(it.key, MUTED, 14), new LinearLayout.LayoutParams(0, -2, 0.45f));
                    TextView v = text(it.value, color(it.color), 14);
                    v.setTextIsSelectable(true);
                    r.addView(v, new LinearLayout.LayoutParams(0, -2, 0.55f));
                    content.addView(r);
                    RowH rh = new RowH();
                    rh.value = v;
                    holders.add(rh);
                    break;
                }
                case 2: {
                    odd = !odd;
                    LinearLayout r = new LinearLayout(this);
                    r.setOrientation(LinearLayout.VERTICAL);
                    r.setBackgroundColor(odd ? ROW1 : ROW2);
                    r.setPadding(dp(12), dp(6), dp(12), dp(8));
                    LinearLayout top = new LinearLayout(this);
                    top.addView(text(it.key, MUTED, 14), new LinearLayout.LayoutParams(0, -2, 0.45f));
                    TextView val = text(it.value, TEXT, 14);
                    top.addView(val, new LinearLayout.LayoutParams(0, -2, 0.55f));
                    r.addView(top);
                    ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                    pb.setMax(1000);
                    GradientDrawable back = new GradientDrawable();
                    back.setColor(BAR_BG);
                    back.setCornerRadius(dp(3));
                    GradientDrawable fill = new GradientDrawable();
                    fill.setColor(it.color == Section.NORMAL ? INFOC : color(it.color));
                    fill.setCornerRadius(dp(3));
                    LayerDrawable ld = new LayerDrawable(new Drawable[]{
                            back, new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)});
                    ld.setId(0, android.R.id.background);
                    ld.setId(1, android.R.id.progress);
                    pb.setProgressDrawable(ld);
                    pb.setProgress((int) (it.pct * 10));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(6));
                    lp.topMargin = dp(5);
                    r.addView(pb, lp);
                    content.addView(r);
                    BarH bh = new BarH();
                    bh.value = val; bh.pb = pb; bh.fill = fill;
                    holders.add(bh);
                    break;
                }
                case 3: {
                    TextView n = text(it.value, color(it.color), 13);
                    n.setPadding(dp(12), dp(6), dp(12), dp(6));
                    n.setTextIsSelectable(true);
                    content.addView(n);
                    NoteH nh = new NoteH();
                    nh.text = n;
                    holders.add(nh);
                    break;
                }
                case 5: {
                    final String id = it.key;
                    Button b = makeButton(it.value);
                    b.setTextSize(15);
                    ((GradientDrawable) b.getBackground()).setColor(it.color != 0 ? ACCENT : BTN);
                    b.setOnClickListener(v -> onAction(id));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(46));
                    lp.setMargins(dp(12), dp(6), dp(12), dp(6));
                    content.addView(b, lp);
                    ActionH ah = new ActionH();
                    ah.button = b;
                    holders.add(ah);
                    break;
                }
                case 4: {
                    TableH th = new TableH();
                    content.addView(tableRow(it.headers, it.ratios, SECTION, true, null));
                    boolean o = false;
                    for (String[] row : it.rows) {
                        o = !o;
                        TextView[] cells = new TextView[row.length];
                        content.addView(tableRow(row, it.ratios, o ? ROW1 : ROW2, false, cells));
                        th.cells.add(cells);
                    }
                    holders.add(th);
                    break;
                }
                default:
                    holders.add(null);
            }
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    LinearLayout tableRow(String[] cells, float[] ratios, int bg, boolean head, TextView[] out) {
        LinearLayout r = new LinearLayout(this);
        r.setBackgroundColor(bg);
        r.setPadding(dp(8), dp(5), dp(8), dp(5));
        for (int i = 0; i < cells.length; i++) {
            float w = ratios != null && i < ratios.length ? ratios[i] : 1f / cells.length;
            TextView t = text(cells[i] == null ? "—" : cells[i], head ? TEXT : (i == 0 ? MUTED : TEXT), 13);
            if (head) t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(dp(4), 0, dp(4), 0);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, w));
            if (out != null) out[i] = t;
        }
        return r;
    }

    TextView text(String s, int color, float size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        return t;
    }

    // ---------- отчёт ----------

    static final String GH = "github";
    static final String DEFAULT_REPO = "Ne-collab228/Test2";
    static final String DEFAULT_BRANCH = "device-info-reports";

    long versionCode() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
        } catch (Throwable e) { return 0; }
    }

    String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Throwable e) { return "?"; }
    }

    /** Полный текст отчёта по всем вкладкам. Выполнять в фоне. */
    String buildReport(Context app) {
        StringBuilder sb = new StringBuilder();
        sb.append("Device Info ").append(versionName()).append(" — отчёт\n")
                .append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n")
                .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", java.util.Locale.US)
                        .format(new java.util.Date())).append('\n');
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            Section s = buildSafe(p, app);
            if (p instanceof SensorsPage) {
                // датчики начинают присылать данные после регистрации — даём им 2 с
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                s = buildSafe(p, app);
                if (i != current) { try { synchronized (p) { p.onHide(app); } } catch (Throwable ignored) { } }
            }
            sb.append("\n===== ").append(p.title()).append(" =====\n").append(s.toText());
        }
        return sb.toString();
    }

    void reportMenu() {
        String[] items = {"Поделиться текстом", "Загрузить в GitHub", "Настройки GitHub"};
        new android.app.AlertDialog.Builder(this)
                .setTitle("Отчёт")
                .setItems(items, (d, w) -> {
                    if (w == 0) shareReport();
                    else if (w == 1) uploadReport(false);
                    else githubSettings(null);
                })
                .show();
    }

    void shareReport() {
        say("Собираю отчёт…", 60000);
        final Context app = getApplicationContext();
        reporter.execute(() -> {
            String text = buildReport(app);
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, "Device Info: " + Build.MODEL);
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Отправить отчёт"));
                say("Отчёт готов", 5000);
            });
        });
    }

    /** Сообщение в нижней строке, видимое не меньше ms миллисекунд. */
    void say(String msg, long ms) {
        statusMsg = msg;
        statusUntil = System.currentTimeMillis() + ms;
        status.setText(msg);
    }

    android.content.SharedPreferences gh() { return getSharedPreferences(GH, MODE_PRIVATE); }

    /** После обновления приложения отчёт один раз загружается в GitHub автоматически. */
    void autoUploadIfUpdated() {
        if (isFinishing()) return;
        long vc = versionCode();
        android.content.SharedPreferences p = gh();
        if (p.getLong("uploaded_version", -1) == vc) return;
        if (p.getString("token", "").isEmpty()) {
            if (p.getLong("prompted_version", -1) != vc) {
                p.edit().putLong("prompted_version", vc).apply();
                new android.app.AlertDialog.Builder(this)
                        .setTitle("Автозагрузка отчёта")
                        .setMessage("Приложение может само загружать отчёт файлом в GitHub после каждого "
                                + "обновления. Для этого нужен токен доступа GitHub (вводится один раз, "
                                + "хранится только на телефоне). Настроить сейчас?")
                        .setPositiveButton("Настроить", (d, w) -> githubSettings(() -> uploadReport(true)))
                        .setNegativeButton("Позже", null)
                        .show();
            }
            return;
        }
        uploadReport(true);
    }

    void uploadReport(boolean auto) {
        android.content.SharedPreferences p = gh();
        String token = p.getString("token", "");
        if (token.isEmpty()) { githubSettings(() -> uploadReport(auto)); return; }
        String repo = p.getString("repo", DEFAULT_REPO);
        String branch = p.getString("branch", DEFAULT_BRANCH);
        say("Загрузка в GitHub: собираю отчёт…", 120000);
        final Context app = getApplicationContext();
        final long vc = versionCode();
        final String vn = versionName();
        reporter.execute(() -> {
            String text = GitHubUploader.redact(buildReport(app));
            String ts = new java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.US).format(new java.util.Date());
            String model = Build.MODEL.replaceAll("[^A-Za-z0-9._-]", "_");
            ui.post(() -> say("Загрузка в GitHub: отправляю…", 120000));
            String path = "device-reports/" + model + "/" + ts + "_v" + vn + (auto ? "_auto" : "") + ".txt";
            GitHubUploader.Result r = GitHubUploader.upload(token, repo, branch, path, text,
                    "Отчёт Device Info " + vn + ": " + Build.MODEL + (auto ? " (авто после обновления)" : ""));
            ui.post(() -> {
                if (r.ok) {
                    gh().edit().putLong("uploaded_version", vc).apply();
                    say("Отчёт загружен в GitHub", 20000);
                    if (!auto) new android.app.AlertDialog.Builder(this)
                            .setTitle("Отчёт загружен")
                            .setMessage("Файл: " + path + "\nВетка: " + branch + "\n\n" + r.message)
                            .setPositiveButton("OK", null).show();
                    else android.widget.Toast.makeText(this, "Отчёт загружен в GitHub", android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    say("GitHub: ошибка — " + r.message, 60000);
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("Не удалось загрузить отчёт")
                            .setMessage(r.message + "\n\nРепозиторий: " + repo + "\nВетка: " + branch
                                    + "\n\nПроверьте токен: доступ к репозиторию Test2 и право Contents: Read and write.")
                            .setPositiveButton("Настройки", (d, w) -> githubSettings(null))
                            .setNegativeButton("Закрыть", null).show();
                }
            });
        });
    }

    void githubSettings(Runnable after) {
        android.content.SharedPreferences p = gh();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        TextView hint = new TextView(this);
        hint.setText("Токен: github.com → Settings → Developer settings → Personal access tokens → "
                + "Fine-grained tokens → Generate. Repository access: только " + DEFAULT_REPO
                + ", Permissions → Contents: Read and write. Токен хранится только на этом телефоне.");
        hint.setTextSize(13);
        box.addView(hint);
        android.widget.EditText token = new android.widget.EditText(this);
        token.setHint("github_pat_…");
        token.setSingleLine(true);
        token.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setText(p.getString("token", ""));
        box.addView(token);
        android.widget.EditText repo = new android.widget.EditText(this);
        repo.setHint("владелец/репозиторий");
        repo.setSingleLine(true);
        repo.setText(p.getString("repo", DEFAULT_REPO));
        box.addView(repo);
        android.widget.EditText branch = new android.widget.EditText(this);
        branch.setHint("ветка");
        branch.setSingleLine(true);
        branch.setText(p.getString("branch", DEFAULT_BRANCH));
        box.addView(branch);
        new android.app.AlertDialog.Builder(this)
                .setTitle("Загрузка отчёта в GitHub")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    p.edit().putString("token", token.getText().toString().trim())
                            .putString("repo", repo.getText().toString().trim().isEmpty() ? DEFAULT_REPO : repo.getText().toString().trim())
                            .putString("branch", branch.getText().toString().trim().isEmpty() ? DEFAULT_BRANCH : branch.getText().toString().trim())
                            .apply();
                    if (after != null && !token.getText().toString().trim().isEmpty()) after.run();
                })
                .setNeutralButton("Удалить токен", (d, w) -> p.edit().remove("token").apply())
                .setNegativeButton("Отмена", null)
                .show();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(tick);
        final Page p = pages.get(current);
        final Context app = getApplicationContext();
        worker.execute(() -> { try { synchronized (p) { p.onHide(app); } } catch (Throwable ignored) { } });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        background.shutdownNow();
        reporter.shutdownNow();
    }
}
