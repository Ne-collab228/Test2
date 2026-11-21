package com.deviceinfo.app;

import android.content.Context;

/** Вкладка приложения. build() вызывается из фонового потока, без доступа к View. */
public interface Page {
    String title();

    void build(Context ctx, Section out) throws Exception;

    /** Обновлять ли вкладку по таймеру (раз в 1.5 с). */
    default boolean live() { return true; }
}
