package com.deviceinfo.app;

import android.content.Context;

public class BatteryPage implements Page {
    @Override public String title() { return "Батарея"; }

    @Override public void build(Context ctx, Section out) {
        out.header("Батарея");
        out.note("В разработке");
    }
}
