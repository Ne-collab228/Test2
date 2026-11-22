package com.deviceinfo.app;

/** История значений для живых графиков: последние windowMs миллисекунд (не больше cap точек). */
public final class Series {
    private final int cap;
    private final long windowMs;
    private final double[] v;
    private final long[] t;
    private int n, head;

    public Series(int cap, long windowMs) {
        this.cap = cap; this.windowMs = windowMs;
        v = new double[cap]; t = new long[cap];
    }

    /** Добавить точку; NaN пропускается. */
    public synchronized void add(long nowMs, double x) {
        if (Double.isNaN(x)) return;
        v[head] = x; t[head] = nowMs;
        head = (head + 1) % cap;
        if (n < cap) n++;
    }

    /** Точки за окно, от старых к новым. */
    public synchronized double[] values(long nowMs) {
        int start = (head - n + cap) % cap, k = 0;
        double[] tmp = new double[n];
        for (int i = 0; i < n; i++) {
            int j = (start + i) % cap;
            if (t[j] >= nowMs - windowMs && t[j] <= nowMs) tmp[k++] = v[j];
        }
        double[] r = new double[k];
        System.arraycopy(tmp, 0, r, 0, k);
        return r;
    }

    public synchronized double last() { return n == 0 ? Double.NaN : v[(head - 1 + cap) % cap]; }

    /** Время последней точки (−1 — точек нет). */
    public synchronized long lastT() { return n == 0 ? -1 : t[(head - 1 + cap) % cap]; }

    public synchronized void clear() { n = 0; head = 0; }
}
