package com.deviceinfo.app;

import android.content.Context;

public class GpuPage implements Page {
    @Override public String title() { return "GPU"; }

    @Override public void build(Context ctx, Section out) {
        out.header("GPU");
        out.note("В разработке");
    }
}
