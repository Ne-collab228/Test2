package com.deviceinfo.app;

import android.content.Context;

/** Вкладка приложения. build() вызывается из фонового потока, без доступа к View. */
public interface Page {
    String title();

    void build(Context ctx, Section out) throws Exception;

    /** Обновлять ли вкладку по таймеру (раз в 1.5 с). */
    default boolean live() { return true; }

    /** Вкладку скрыли / приложение свернули: освободить ресурсы (слушатели датчиков и т.п.). */
    default void onHide(Context ctx) { }

    /**
     * Фоновый замер раз в 1.5 с, пока приложение открыто, независимо от выбранной вкладки
     * (например, накопление тока батареи для измерения ёмкости). Вызывается в фоне.
     */
    default void tick(Context ctx) { }
}
