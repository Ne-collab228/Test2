package com.deviceinfo.app;

import java.util.ArrayList;
import java.util.List;

/** Список вкладок в порядке отображения. */
public final class Pages {
    private Pages() {}

    public static List<Page> create() {
        List<Page> l = new ArrayList<>();
        l.add(new CpuPage());
        l.add(new GpuPage());
        l.add(new BatteryPage());
        l.add(new EnergyPage());
        l.add(new DisplayPage());
        l.add(new MemoryPage());
        l.add(new ThermalPage());
        l.add(new SystemPage());
        l.add(new CameraPage());
        l.add(new SensorsPage());
        return l;
    }
}
