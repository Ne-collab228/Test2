package com.deviceinfo.app;

import android.content.Context;

public class CpuPage implements Page {
    @Override public String title() { return "Процессор"; }

    @Override public void build(Context ctx, Section out) {
        out.header("Процессор");
        out.note("В разработке");
    }
}
