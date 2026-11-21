package com.deviceinfo.app;

import android.content.Context;

public class CameraPage implements Page {
    @Override public String title() { return "Камеры"; }

    @Override public void build(Context ctx, Section out) {
        out.header("Камеры");
        out.note("В разработке");
    }
}
