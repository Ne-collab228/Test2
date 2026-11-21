package com.deviceinfo.app;

import android.content.Context;

public class DisplayPage implements Page {
    @Override public String title() { return "Экран"; }

    @Override public void build(Context ctx, Section out) {
        out.header("Экран");
        out.note("В разработке");
    }
}
