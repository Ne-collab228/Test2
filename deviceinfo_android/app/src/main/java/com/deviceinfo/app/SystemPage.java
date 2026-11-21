package com.deviceinfo.app;

import android.content.Context;

public class SystemPage implements Page {
    @Override public String title() { return "Система"; }

    @Override public void build(Context ctx, Section out) {
        out.header("Система");
        out.note("В разработке");
    }
}
