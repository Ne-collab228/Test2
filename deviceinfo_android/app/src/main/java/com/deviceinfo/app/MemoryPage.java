package com.deviceinfo.app;

import android.content.Context;

public class MemoryPage implements Page {
    @Override public String title() { return "Память"; }

    @Override public void build(Context ctx, Section out) {
        out.header("Память");
        out.note("В разработке");
    }
}
