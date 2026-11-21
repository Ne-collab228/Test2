package com.deviceinfo.app;

import android.content.Context;

public class SensorsPage implements Page {
    @Override public String title() { return "Датчики"; }

    @Override public void build(Context ctx, Section out) {
        out.header("Датчики");
        out.note("В разработке");
    }
}
