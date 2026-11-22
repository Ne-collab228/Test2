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
    /** Цвета текущей темы (настройки: тема, акцент). */
    Theme th;
    /** Интервал автообновления, мс (настройки, по умолчанию 1 с). */
    long period = Theme.DEFAULT_PERIOD;

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
    private final List<LinearLayout> tabButtons = new ArrayList<>();
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
            ui.postDelayed(this, period);
        }
    };

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        th = Theme.load(this);
        period = Theme.period(this);
        getWindow().getDecorView().setBackgroundColor(th.bg);
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                getWindow().getInsetsController().setSystemBarsAppearance(th.dark ? 0 : light, light);
            } catch (Throwable ignored) { }
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(th.bg);
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

        // Шапка: название телефона и кнопки «Отчёт», «Настройки»
        LinearLayout head = new LinearLayout(this);
        head.setPadding(dp(16), dp(10), dp(10), dp(4));
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        String market = Util.firstNonEmpty(Util.prop("ro.product.marketname"), Util.prop("ro.config.marketing_name"));
        title.setText(market != null ? market + "  ·  " + Build.MODEL : Build.MANUFACTURER.toUpperCase() + " " + Build.MODEL);
        title.setTextColor(th.text);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        View report = iconButton(R.drawable.ic_report, "Отчёт");
        report.setOnClickListener(v -> reportMenu());
        head.addView(report);
        View settings = iconButton(R.drawable.ic_settings, "Настройки");
        settings.setOnClickListener(v -> settingsDialog());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-2, -2);
        slp.leftMargin = dp(8);
        head.addView(settings, slp);
        root.addView(head);

        // Вкладки-«таблетки» с иконками
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(this);
        tabs.setPadding(dp(10), dp(8), dp(10), dp(8));
        for (int i = 0; i < pages.size(); i++) {
            final int idx = i;
            LinearLayout t = tabPill(iconFor(pages.get(i)), pages.get(i).title());
            t.setOnClickListener(v -> switchTab(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(44));
            lp.setMargins(dp(3), 0, dp(3), 0);
            tabs.addView(t, lp);
            tabButtons.add(t);
        }
        hs.addView(tabs);
        root.addView(hs);

        scroll = new ScrollView(this);
        scroll.setBackgroundColor(th.bg);
        scroll.setVerticalScrollBarEnabled(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), 0, dp(16), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        status = new TextView(this);
        status.setTextColor(th.muted);
        status.setTextSize(12);
        status.setBackgroundColor(th.bg);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(10), dp(4), dp(10), dp(6));
        root.addView(status);

        setContentView(root);
        switchTab(0);
        prewarm();
        ui.postDelayed(this::autoUploadIfUpdated, 6000);
    }

    static int iconFor(Page p) {
        if (p instanceof CpuPage) return R.drawable.ic_cpu;
        if (p instanceof GpuPage) return R.drawable.ic_gpu;
        if (p instanceof BatteryPage) return R.drawable.ic_battery;
        if (p instanceof EnergyPage) return R.drawable.ic_energy;
        if (p instanceof DisplayPage) return R.drawable.ic_display;
        if (p instanceof MemoryPage) return R.drawable.ic_memory;
        if (p instanceof ThermalPage) return R.drawable.ic_thermo;
        if (p instanceof CameraPage) return R.drawable.ic_camera;
        if (p instanceof SensorsPage) return R.drawable.ic_sensors;
        return R.drawable.ic_system;
    }

    GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    View iconButton(int icon, String desc) {
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(th.text);
        iv.setContentDescription(desc);
        iv.setPadding(dp(9), dp(9), dp(9), dp(9));
        iv.setBackground(round(th.surface, 14));
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return iv;
    }

    LinearLayout tabPill(int icon, String label) {
        LinearLayout t = new LinearLayout(this);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setPadding(dp(14), 0, dp(16), 0);
        t.setBackground(round(0x00000000, 22));
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setImageResource(icon);
        t.addView(iv, new LinearLayout.LayoutParams(dp(20), dp(20)));
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        t.addView(tv, lp);
        return t;
    }

    void styleTab(LinearLayout t, boolean on) {
        ((GradientDrawable) t.getBackground()).setColor(on ? th.accent : 0x00000000);
        int fg = on ? th.onAccent : th.muted;
        ((android.widget.ImageView) t.getChildAt(0)).setColorFilter(fg);
        ((TextView) t.getChildAt(1)).setTextColor(fg);
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

    Button makeButton(String text, boolean primary) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(primary ? th.onAccent : th.text);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setStateListAnimator(null);
        b.setBackground(round(primary ? th.accent : th.surface2, 14));
        return b;
    }

    void switchTab(int idx) {
        if (idx != current) {
            final Page old = pages.get(current);
            final Context app = getApplicationContext();
            worker.execute(() -> { try { synchronized (old) { old.onHide(app); } } catch (Throwable ignored) { } });
        }
        current = idx;
        for (int i = 0; i < tabButtons.size(); i++) styleTab(tabButtons.get(i), i == idx);
        shownSignature = null;
        Section cached = cache.get(idx);
        if (cached != null) {
            render(cached, false);
        } else {
            content.removeAllViews();
            holders.clear();
            TextView wait = text("Загрузка…", th.muted, 15);
            wait.setPadding(dp(4), dp(20), dp(4), dp(20));
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
        } else if ("energy_start".equals(id)) {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                try { requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2); } catch (Throwable ignored) { }
            }
            EnergyService.start(this);
        } else if ("energy_stop".equals(id)) {
            EnergyService.stop(this);
        } else if ("energy_reset".equals(id)) {
            new android.app.AlertDialog.Builder(this, dialogTheme()).setTitle("Сбросить статистику расхода?")
                    .setMessage("Накопленные данные о расходе с экраном выключенным и включённым будут удалены.")
                    .setPositiveButton("Сбросить", (d, w) -> {
                        EnergyStats.reset(this);
                        if (EnergyService.running) EnergyService.stats = new EnergyStats();
                        refresh();
                    })
                    .setNegativeButton("Отмена", null).show();
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

    int color(int c) { return th.color(c); }

    /** Структура вкладки: если она не изменилась, значения обновляются в уже созданных View. */
    static String signature(Section s) {
        StringBuilder sb = new StringBuilder();
        for (Section.Item it : s.items) {
            sb.append(it.type).append('|');
            if (it.type == 0 || it.type == 1 || it.type == 2 || it.type == 5 || it.type == 6) sb.append(it.key);
            if (it.type == 0) sb.append(it.value == null ? "" : "#");
            if (it.type == 1) sb.append(stacked(it.key, it.value) ? "#v" : "#h");
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
    static final class HeaderH { TextView big; }
    static final class ChartH { ChartView chart; TextView value; }

    /** Длинное значение показывается под названием, а не справа. */
    static boolean stacked(String key, String value) {
        if (value == null) return false;
        return value.indexOf('\n') >= 0 || key.length() + value.length() > 34 || value.length() > 24;
    }

    void render(Section s, boolean live) {
        String sig = signature(s);
        if (live && sig.equals(shownSignature) && holders.size() == s.items.size()) {
            update(s);
        } else {
            rebuild(s);
            shownSignature = sig;
        }
        if (statusMsg != null && System.currentTimeMillis() < statusUntil) { status.setText(statusMsg); return; }
        status.setText("Обновлено " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date()) + (pages.get(current).live() ? "  •  каждые " + periodText(period) : ""));
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
                    bh.fill.setColor(color(it.color));
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
                case 0: {
                    HeaderH hh = (HeaderH) h;
                    if (hh != null && hh.big != null && it.value != null) {
                        setText(hh.big, it.value);
                        hh.big.setTextColor(it.color == Section.NORMAL ? th.text : color(it.color));
                    }
                    break;
                }
                case 6: {
                    ChartH ch = (ChartH) h;
                    ch.chart.set(it.series, it.lo, it.hi, it.loLabel, it.hiLabel, color(it.color));
                    setText(ch.value, it.value);
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
        for (Section.Item it : s.items) {
            switch (it.type) {
                case 0: {
                    LinearLayout h = new LinearLayout(this);
                    h.setGravity(Gravity.CENTER_VERTICAL);
                    View accentBar = new View(this);
                    accentBar.setBackground(round(th.accent, 3));
                    h.addView(accentBar, new LinearLayout.LayoutParams(dp(5), dp(22)));
                    TextView t = new TextView(this);
                    android.text.SpannableString sp = new android.text.SpannableString(it.key);
                    if (it.key.length() > 0)
                        sp.setSpan(new android.text.style.ForegroundColorSpan(th.accent), 0, 1, 0);
                    t.setText(sp);
                    t.setTextColor(th.text);
                    t.setTextSize(19);
                    t.setTypeface(Typeface.DEFAULT_BOLD);
                    LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
                    tl.leftMargin = dp(10);
                    h.addView(t, tl);
                    HeaderH hh = new HeaderH();
                    if (it.value != null) {
                        TextView big = text(it.value, it.color == Section.NORMAL ? th.text : color(it.color), 26);
                        big.setTypeface(Typeface.DEFAULT_BOLD);
                        h.addView(big);
                        hh.big = big;
                    }
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.topMargin = dp(content.getChildCount() == 0 ? 8 : 26);
                    lp.bottomMargin = dp(6);
                    content.addView(h, lp);
                    holders.add(hh);
                    break;
                }
                case 1: {
                    boolean st = stacked(it.key, it.value);
                    LinearLayout r = new LinearLayout(this);
                    r.setPadding(dp(2), dp(8), 0, dp(8));
                    View dot = new View(this);
                    dot.setBackground(round(Theme.alpha(th.muted, 150), 2));
                    LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(7), dp(7));
                    dl.topMargin = dp(7);
                    dl.rightMargin = dp(12);
                    r.addView(dot, dl);
                    TextView k = text(it.key, th.text, 15);
                    TextView v = text(it.value, color(it.color), 15);
                    v.setTextIsSelectable(true);
                    if (st) {
                        LinearLayout col = new LinearLayout(this);
                        col.setOrientation(LinearLayout.VERTICAL);
                        col.addView(k);
                        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
                        vl.topMargin = dp(5);
                        col.addView(v, vl);
                        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1));
                    } else {
                        r.addView(k, new LinearLayout.LayoutParams(0, -2, 1));
                        v.setGravity(Gravity.END);
                        LinearLayout.LayoutParams vl = new LinearLayout.LayoutParams(-2, -2);
                        vl.leftMargin = dp(12);
                        r.addView(v, vl);
                    }
                    content.addView(r);
                    RowH rh = new RowH();
                    rh.value = v;
                    holders.add(rh);
                    break;
                }
                case 2: {
                    LinearLayout r = new LinearLayout(this);
                    r.setOrientation(LinearLayout.VERTICAL);
                    r.setPadding(dp(2), dp(8), 0, dp(10));
                    LinearLayout top = new LinearLayout(this);
                    top.addView(text(it.key, th.text, 15), new LinearLayout.LayoutParams(0, -2, 1));
                    TextView val = text(it.value, th.text, 15);
                    val.setTypeface(Typeface.DEFAULT_BOLD);
                    top.addView(val);
                    r.addView(top);
                    ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                    pb.setMax(1000);
                    GradientDrawable back = round(th.surface2, 5);
                    GradientDrawable fill = round(color(it.color), 5);
                    LayerDrawable ld = new LayerDrawable(new Drawable[]{
                            back, new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)});
                    ld.setId(0, android.R.id.background);
                    ld.setId(1, android.R.id.progress);
                    pb.setProgressDrawable(ld);
                    pb.setProgress((int) (it.pct * 10));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(10));
                    lp.topMargin = dp(7);
                    r.addView(pb, lp);
                    content.addView(r);
                    BarH bh = new BarH();
                    bh.value = val; bh.pb = pb; bh.fill = fill;
                    holders.add(bh);
                    break;
                }
                case 3: {
                    TextView n = text(it.value, it.color == Section.MUTED ? th.muted : color(it.color), 13);
                    n.setPadding(dp(14), dp(10), dp(14), dp(10));
                    n.setBackground(round(th.surface, 12));
                    n.setLineSpacing(0, 1.1f);
                    n.setTextIsSelectable(true);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.setMargins(0, dp(6), 0, dp(6));
                    content.addView(n, lp);
                    NoteH nh = new NoteH();
                    nh.text = n;
                    holders.add(nh);
                    break;
                }
                case 5: {
                    final String id = it.key;
                    Button b = makeButton(it.value, it.color != 0);
                    b.setOnClickListener(v -> onAction(id));
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
                    lp.setMargins(0, dp(6), 0, dp(6));
                    content.addView(b, lp);
                    ActionH ah = new ActionH();
                    ah.button = b;
                    holders.add(ah);
                    break;
                }
                case 4: {
                    TableH th2 = new TableH();
                    LinearLayout card = new LinearLayout(this);
                    card.setOrientation(LinearLayout.VERTICAL);
                    card.setBackground(round(th.surface, 12));
                    card.setPadding(0, dp(4), 0, dp(4));
                    card.addView(tableRow(it.headers, it.ratios, true, null));
                    for (String[] row : it.rows) {
                        TextView[] cells = new TextView[row.length];
                        card.addView(tableRow(row, it.ratios, false, cells));
                        th2.cells.add(cells);
                    }
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                    lp.setMargins(0, dp(6), 0, dp(6));
                    content.addView(card, lp);
                    holders.add(th2);
                    break;
                }
                case 6: {
                    LinearLayout box = new LinearLayout(this);
                    box.setOrientation(LinearLayout.VERTICAL);
                    box.setPadding(dp(2), dp(6), 0, dp(6));
                    LinearLayout top = new LinearLayout(this);
                    top.addView(text(it.key, th.muted, 13), new LinearLayout.LayoutParams(0, -2, 1));
                    TextView val = text(it.value, color(it.color), 13);
                    val.setTypeface(Typeface.DEFAULT_BOLD);
                    top.addView(val);
                    box.addView(top);
                    ChartView cv = new ChartView(this, th);
                    cv.set(it.series, it.lo, it.hi, it.loLabel, it.hiLabel, color(it.color));
                    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
                    cl.topMargin = dp(4);
                    box.addView(cv, cl);
                    content.addView(box);
                    ChartH ch = new ChartH();
                    ch.chart = cv; ch.value = val;
                    holders.add(ch);
                    break;
                }
                default:
                    holders.add(null);
            }
        }
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    LinearLayout tableRow(String[] cells, float[] ratios, boolean head, TextView[] out) {
        LinearLayout r = new LinearLayout(this);
        r.setPadding(dp(10), dp(6), dp(10), dp(6));
        for (int i = 0; i < cells.length; i++) {
            float w = ratios != null && i < ratios.length ? ratios[i] : 1f / cells.length;
            TextView t = text(cells[i] == null ? "—" : cells[i],
                    head ? th.muted : (i == 0 ? th.text : th.accent), head ? 12 : 13);
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

    static String periodText(long ms) {
        return ms % 1000 == 0 ? (ms / 1000) + " с" : String.format(java.util.Locale.US, "%.1f с", ms / 1000.0).replace('.', ',');
    }

    int dialogTheme() {
        return th.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    // ---------- настройки ----------

    /** Окно настроек: тема, цвет акцента, частота обновления, отчёт в GitHub. */
    void settingsDialog() {
        final android.content.SharedPreferences sp = Theme.prefs(this);
        final int oldMode = Theme.mode(this), oldAccent = Theme.accentIndex(this);
        final int[] mode = {oldMode}, accent = {oldAccent};
        final long[] per = {period};
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(8));
        sv.addView(box);

        box.addView(settingsTitle("Тема"));
        final List<Button> modeBtns = new ArrayList<>();
        LinearLayout modes = new LinearLayout(this);
        for (int i = 0; i < Theme.MODE_NAMES.length; i++) {
            final int k = i;
            Button b = chip(Theme.MODE_NAMES[i]);
            b.setOnClickListener(v -> { mode[0] = k; markChoice(modeBtns, k); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            modes.addView(b, lp);
            modeBtns.add(b);
        }
        markChoice(modeBtns, mode[0]);
        box.addView(modes);

        box.addView(settingsTitle("Цвет акцента"));
        final List<View> swatches = new ArrayList<>();
        final TextView accentName = text(Theme.ACCENT_NAMES[accent[0]], th.muted, 13);
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER);
            for (int c = 0; c < 4; c++) {
                final int k = row * 4 + c;
                View sw = new View(this);
                sw.setContentDescription(Theme.ACCENT_NAMES[k]);
                sw.setOnClickListener(v -> {
                    accent[0] = k;
                    markSwatches(swatches, k);
                    accentName.setText(Theme.ACCENT_NAMES[k]);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
                lp.setMargins(dp(10), dp(6), dp(10), dp(6));
                line.addView(sw, lp);
                swatches.add(sw);
            }
            box.addView(line);
        }
        markSwatches(swatches, accent[0]);
        accentName.setGravity(Gravity.CENTER);
        box.addView(accentName, new LinearLayout.LayoutParams(-1, -2));

        box.addView(settingsTitle("Обновление данных"));
        final List<Button> perBtns = new ArrayList<>();
        LinearLayout pers = new LinearLayout(this);
        int sel = 1;
        for (int i = 0; i < Theme.PERIODS.length; i++) {
            final int k = i;
            if (Theme.PERIODS[i] == period) sel = i;
            Button b = chip(periodText(Theme.PERIODS[i]));
            b.setOnClickListener(v -> { per[0] = Theme.PERIODS[k]; markChoice(perBtns, k); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1);
            lp.setMargins(dp(2), 0, dp(2), 0);
            pers.addView(b, lp);
            perBtns.add(b);
        }
        markChoice(perBtns, sel);
        box.addView(pers);
        TextView hint = text("Чаще — плавнее графики, но немного больше расход батареи.", th.muted, 12);
        hint.setPadding(0, dp(6), 0, 0);
        box.addView(hint);

        box.addView(settingsTitle("Плавающее окно"));
        final android.widget.Switch ov = new android.widget.Switch(this);
        ov.setText("Плавающее окно поверх игр");
        ov.setTextColor(th.text);
        ov.setChecked(OverlayService.running);
        ov.setOnCheckedChangeListener((b, on) -> { if (b.isPressed()) setOverlay(on, ov); });
        box.addView(ov);
        TextView ovHint = text("заряд, ток, температура, частота ядер", th.muted, 12);
        ovHint.setPadding(0, dp(4), 0, 0);
        box.addView(ovHint);

        box.addView(settingsTitle("Отчёт"));
        final android.widget.Switch auto = new android.widget.Switch(this);
        auto.setText("Загружать отчёт в GitHub после обновления");
        auto.setTextColor(th.text);
        auto.setChecked(gh().getBoolean("auto_upload", true));
        box.addView(auto);
        Button gb = makeButton("Настройки GitHub (токен)", false);
        gb.setOnClickListener(v -> githubSettings(null));
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-1, dp(44));
        gl.topMargin = dp(8);
        box.addView(gb, gl);
        TextView ver = text("Device Info " + versionName(), th.muted, 12);
        ver.setPadding(0, dp(14), 0, 0);
        box.addView(ver);

        new android.app.AlertDialog.Builder(this, dialogTheme())
                .setTitle("Настройки")
                .setView(sv)
                .setPositiveButton("Готово", (d, w) -> {
                    sp.edit().putInt("mode", mode[0]).putInt("accent", accent[0]).putLong("period", per[0]).apply();
                    gh().edit().putBoolean("auto_upload", auto.isChecked()).apply();
                    period = per[0];
                    if (mode[0] != oldMode || accent[0] != oldAccent) recreate();
                    else { ui.removeCallbacks(tick); if (resumed) ui.post(tick); }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Включить/выключить плавающее окно; без разрешения «Поверх других окон» ведёт в системные настройки. */
    void setOverlay(boolean on, android.widget.Switch sw) {
        final android.content.SharedPreferences op = getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE);
        if (!on) {
            op.edit().putBoolean("enabled", false).apply();
            stopService(new Intent(this, OverlayService.class));
            return;
        }
        op.edit().putBoolean("enabled", true).apply();
        if (android.provider.Settings.canDrawOverlays(this)) { startOverlay(); return; }
        sw.setChecked(false);
        new android.app.AlertDialog.Builder(this, dialogTheme())
                .setTitle("Нужно разрешение")
                .setMessage("Чтобы показывать окно поверх игр, разрешите приложению «Поверх других окон». "
                        + "Откроются системные настройки: включите переключатель и вернитесь в приложение.")
                .setPositiveButton("Открыть настройки", (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (Throwable e) { toastStatus("Не удалось открыть настройки: " + e.getMessage()); }
                })
                .setNegativeButton("Отмена", (d, w) -> op.edit().putBoolean("enabled", false).apply())
                .show();
    }

    void startOverlay() {
        try { startForegroundService(new Intent(this, OverlayService.class)); }
        catch (Throwable e) { toastStatus("Не удалось включить окно: " + e.getMessage()); }
    }

    /** Кнопка-переключатель в настройках: одна строка, без лишних отступов. */
    Button chip(String text) {
        Button b = makeButton(text, false);
        b.setTextSize(13);
        b.setMaxLines(1);
        b.setPadding(dp(2), 0, dp(2), 0);
        return b;
    }

    TextView settingsTitle(String s) {
        TextView t = text(s, th.text, 15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(16), 0, dp(8));
        return t;
    }

    void markChoice(List<Button> l, int sel) {
        for (int i = 0; i < l.size(); i++) {
            Button b = l.get(i);
            ((GradientDrawable) b.getBackground()).setColor(i == sel ? th.accent : th.surface2);
            b.setTextColor(i == sel ? th.onAccent : th.text);
        }
    }

    void markSwatches(List<View> l, int sel) {
        for (int i = 0; i < l.size(); i++) {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(Theme.ACCENTS[i]);
            if (i == sel) g.setStroke(dp(3), th.text);
            l.get(i).setBackground(g);
        }
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
        new android.app.AlertDialog.Builder(this, dialogTheme())
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
        if (!p.getBoolean("auto_upload", true)) return;
        if (p.getString("token", "").isEmpty()) {
            if (p.getLong("prompted_version", -1) != vc) {
                p.edit().putLong("prompted_version", vc).apply();
                new android.app.AlertDialog.Builder(this, dialogTheme())
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
                    if (!auto) new android.app.AlertDialog.Builder(this, dialogTheme())
                            .setTitle("Отчёт загружен")
                            .setMessage("Файл: " + path + "\nВетка: " + branch + "\n\n" + r.message)
                            .setPositiveButton("OK", null).show();
                    else android.widget.Toast.makeText(this, "Отчёт загружен в GitHub", android.widget.Toast.LENGTH_SHORT).show();
                } else {
                    say("GitHub: ошибка — " + r.message, 60000);
                    new android.app.AlertDialog.Builder(this, dialogTheme())
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
        new android.app.AlertDialog.Builder(this, dialogTheme())
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
        if (!OverlayService.running && getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE).getBoolean("enabled", false)
                && android.provider.Settings.canDrawOverlays(this)) startOverlay();
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
