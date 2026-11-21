package com.deviceinfo.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    static final int BG = 0xFF121417, HEADER = 0xFF1A1D22, SECTION = 0xFF2A2F37,
            ROW1 = 0xFF1D2127, ROW2 = 0xFF23282F, ACCENT = 0xFFE53935, TEXT = 0xFFECEFF1,
            MUTED = 0xFF9AA5B1, OKC = 0xFF43A047, WARNC = 0xFFFB8C00, INFOC = 0xFF1E88E5,
            BTN = 0xFF2F353E, BAR_BG = 0xFF33383F;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Page> pages = Pages.create();
    private final List<Button> tabButtons = new ArrayList<>();
    private LinearLayout content;
    private ScrollView scroll;
    private TextView status;
    private int current = 0;
    private boolean resumed, busy;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if (pages.get(current).live()) refresh();
            ui.postDelayed(this, 1500);
        }
    };

    int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(HEADER);
        getWindow().setNavigationBarColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // Шапка
        LinearLayout head = new LinearLayout(this);
        head.setBackgroundColor(HEADER);
        head.setPadding(dp(12), dp(10), dp(8), dp(10));
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(Build.MANUFACTURER.toUpperCase() + " " + Build.MODEL);
        title.setTextColor(TEXT);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button report = makeButton("Отчёт");
        report.setOnClickListener(v -> shareReport());
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
        current = idx;
        for (int i = 0; i < tabButtons.size(); i++) {
            GradientDrawable bg = (GradientDrawable) tabButtons.get(i).getBackground();
            bg.setColor(i == idx ? ACCENT : BTN);
        }
        content.removeAllViews();
        scroll.scrollTo(0, 0);
        refresh();
    }

    void refresh() {
        if (busy) return;
        busy = true;
        final int idx = current;
        final Page page = pages.get(idx);
        worker.execute(() -> {
            Section s = buildSafe(page);
            ui.post(() -> {
                busy = false;
                if (idx == current && !isFinishing()) render(s);
            });
        });
    }

    Section buildSafe(Page page) {
        Section s = new Section();
        try {
            page.build(getApplicationContext(), s);
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

    void render(Section s) {
        int y = scroll.getScrollY();
        content.removeAllViews();
        boolean odd = false;
        for (Section.Item it : s.items) {
            switch (it.type) {
                case 0: {
                    View gap = new View(this);
                    content.addView(gap, new LinearLayout.LayoutParams(-1, dp(6)));
                    TextView h = new TextView(this);
                    h.setText(it.key);
                    h.setTextColor(TEXT);
                    h.setTypeface(Typeface.DEFAULT_BOLD);
                    h.setTextSize(15);
                    h.setBackgroundColor(SECTION);
                    h.setPadding(dp(12), dp(8), dp(12), dp(8));
                    content.addView(h);
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
                    top.addView(text(it.value, TEXT, 14), new LinearLayout.LayoutParams(0, -2, 0.55f));
                    r.addView(top);
                    ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                    pb.setMax(1000);
                    pb.setProgress((int) (it.pct * 10));
                    GradientDrawable back = new GradientDrawable();
                    back.setColor(BAR_BG);
                    back.setCornerRadius(dp(3));
                    GradientDrawable fill = new GradientDrawable();
                    fill.setColor(it.color == Section.NORMAL ? INFOC : color(it.color));
                    fill.setCornerRadius(dp(3));
                    LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{
                            back, new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)});
                    ld.setId(0, android.R.id.background);
                    ld.setId(1, android.R.id.progress);
                    pb.setProgressDrawable(ld);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(6));
                    lp.topMargin = dp(5);
                    r.addView(pb, lp);
                    content.addView(r);
                    break;
                }
                case 3: {
                    TextView n = text(it.value, color(it.color), 13);
                    n.setPadding(dp(12), dp(6), dp(12), dp(6));
                    n.setTextIsSelectable(true);
                    content.addView(n);
                    break;
                }
                case 4: {
                    content.addView(tableRow(it.headers, it.ratios, SECTION, true));
                    boolean o = false;
                    for (String[] row : it.rows) {
                        o = !o;
                        content.addView(tableRow(row, it.ratios, o ? ROW1 : ROW2, false));
                    }
                    break;
                }
            }
        }
        scroll.post(() -> scroll.scrollTo(0, y));
        status.setText("Обновлено: " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date()) + (pages.get(current).live() ? "  •  авто 1.5 с" : ""));
    }

    LinearLayout tableRow(String[] cells, float[] ratios, int bg, boolean head) {
        LinearLayout r = new LinearLayout(this);
        r.setBackgroundColor(bg);
        r.setPadding(dp(8), dp(5), dp(8), dp(5));
        for (int i = 0; i < cells.length; i++) {
            float w = ratios != null && i < ratios.length ? ratios[i] : 1f / cells.length;
            TextView t = text(cells[i] == null ? "—" : cells[i], head ? TEXT : (i == 0 ? MUTED : TEXT), 13);
            if (head) t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(dp(4), 0, dp(4), 0);
            r.addView(t, new LinearLayout.LayoutParams(0, -2, w));
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

    void shareReport() {
        status.setText("Собираю отчёт…");
        worker.execute(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("Device Info — отчёт\n").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(" (").append(Build.DEVICE).append(")\n");
            for (Page p : pages) {
                sb.append("\n===== ").append(p.title()).append(" =====\n");
                sb.append(buildSafe(p).toText());
            }
            String text = sb.toString();
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, "Device Info: " + Build.MODEL);
                i.putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "Отправить отчёт"));
                status.setText("Отчёт готов");
            });
        });
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        ui.removeCallbacks(tick);
        ui.postDelayed(tick, 1500);
    }

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(tick);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
