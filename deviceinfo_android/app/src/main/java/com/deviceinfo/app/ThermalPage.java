package com.deviceinfo.app;

import android.content.Context;

public class ThermalPage implements Page {
    @Override public String title() { return "Температуры"; }

    @Override public void build(Context ctx, Section out) {
        out.header("Температуры");
        out.note("В разработке");
    }
}
