package com.durak.bt;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import org.godotengine.godot.Godot;
import org.godotengine.godot.plugin.GodotPlugin;
import org.godotengine.godot.plugin.SignalInfo;
import org.godotengine.godot.plugin.UsedByGodot;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bluetooth Classic (RFCOMM) транспорт для «Дурака».
 * Хост принимает до 5 подключений, клиент подключается к хосту (id соединения = 0).
 * Сообщения — произвольные байты, в Godot передаются строкой base64.
 */
@SuppressLint("MissingPermission")
public class DurakBluetoothPlugin extends GodotPlugin {
    private static final String TAG = "DurakBT";
    private static final UUID APP_UUID = UUID.fromString("6f1c9b3e-4c3a-4d7e-9a51-d5a2c3b8e701");
    private static final String SERVICE_NAME = "DurakCardGame";
    private static final int MAX_PEERS = 5;
    private static final int MAX_FRAME = 1 << 20;

    private BluetoothAdapter adapter;
    private BluetoothServerSocket serverSocket;
    private Thread acceptThread;
    private final Map<Integer, Conn> conns = new ConcurrentHashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private int nextId = 1;
    private boolean receiverRegistered = false;
    private WifiManager.MulticastLock multicastLock;

    private static class Conn {
        int id;
        BluetoothSocket socket;
        DataInputStream in;
        DataOutputStream out;
        String name;
    }

    public DurakBluetoothPlugin(Godot godot) {
        super(godot);
        try {
            Context ctx = godot.getContext();
            BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = bm != null ? bm.getAdapter() : null;
        } catch (Throwable t) {
            adapter = null;
        }
    }

    @Override
    public String getPluginName() {
        return "DurakBluetooth";
    }

    @Override
    public Set<SignalInfo> getPluginSignals() {
        Set<SignalInfo> s = new HashSet<>();
        s.add(new SignalInfo("bt_peer_connected", Integer.class, String.class));
        s.add(new SignalInfo("bt_peer_disconnected", Integer.class));
        s.add(new SignalInfo("bt_message", Integer.class, String.class));
        s.add(new SignalInfo("bt_device_found", String.class, String.class));
        s.add(new SignalInfo("bt_error", String.class));
        s.add(new SignalInfo("bt_connected_to_host"));
        s.add(new SignalInfo("bt_connect_failed", String.class));
        return s;
    }

    private void emit(final String name, final Object... args) {
        runOnRenderThread(() -> emitSignal(name, args));
    }

    // ------------------------------------------------------------------ состояние

    @UsedByGodot
    public boolean isAvailable() {
        return adapter != null;
    }

    @UsedByGodot
    public boolean isEnabled() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (SecurityException e) {
            return false;
        }
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    @UsedByGodot
    public boolean hasPermissions() {
        Activity a = getActivity();
        if (a == null) return false;
        if (Build.VERSION.SDK_INT < 23) return true;
        for (String p : requiredPermissions()) {
            if (a.checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }

    @UsedByGodot
    public void requestPermissions() {
        final Activity a = getActivity();
        if (a == null || Build.VERSION.SDK_INT < 23) return;
        runOnUiThread(() -> a.requestPermissions(requiredPermissions(), 4711));
    }

    @UsedByGodot
    public void requestEnable() {
        final Activity a = getActivity();
        if (a == null) return;
        runOnUiThread(() -> {
            try {
                a.startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (Throwable t) {
                emit("bt_error", "Не удалось включить Bluetooth: " + t.getMessage());
            }
        });
    }

    @UsedByGodot
    public void makeDiscoverable() {
        final Activity a = getActivity();
        if (a == null) return;
        runOnUiThread(() -> {
            try {
                Intent i = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
                i.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
                a.startActivity(i);
            } catch (Throwable t) {
                Log.w(TAG, "discoverable", t);
            }
        });
    }

    // ------------------------------------------------------------------ сервер

    @UsedByGodot
    public boolean startServer(String name) {
        closeAll();
        if (adapter == null) return false;
        try {
            serverSocket = adapter.listenUsingInsecureRfcommWithServiceRecord(SERVICE_NAME, APP_UUID);
        } catch (Throwable t) {
            Log.e(TAG, "listen", t);
            emit("bt_error", "Ошибка Bluetooth: " + t.getMessage());
            return false;
        }
        final BluetoothServerSocket ss = serverSocket;
        acceptThread = new Thread(() -> {
            while (true) {
                BluetoothSocket s;
                try {
                    s = ss.accept();
                } catch (IOException e) {
                    break; // сервер закрыт
                }
                if (s == null) continue;
                if (conns.size() >= MAX_PEERS) {
                    try { s.close(); } catch (IOException ignored) { }
                    continue;
                }
                int id;
                synchronized (this) {
                    id = nextId++;
                }
                startConn(id, s, true);
            }
        }, "DurakBT-accept");
        acceptThread.start();
        return true;
    }

    // ------------------------------------------------------------------ поиск

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction())) {
                BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (d == null) return;
                String n = null;
                try {
                    n = d.getName();
                } catch (SecurityException ignored) { }
                emit("bt_device_found", d.getAddress(), n == null ? "" : n);
            }
        }
    };

    @UsedByGodot
    public String getPairedDevices() {
        JSONArray arr = new JSONArray();
        if (adapter == null) return arr.toString();
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                JSONObject o = new JSONObject();
                o.put("address", d.getAddress());
                String n = d.getName();
                o.put("name", n == null ? d.getAddress() : n);
                arr.put(o);
            }
        } catch (Throwable t) {
            Log.w(TAG, "paired", t);
        }
        return arr.toString();
    }

    @UsedByGodot
    public void startDiscovery() {
        if (adapter == null) return;
        Activity a = getActivity();
        if (a == null) return;
        try {
            if (!receiverRegistered) {
                IntentFilter f = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                if (Build.VERSION.SDK_INT >= 33) {
                    a.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED);
                } else {
                    a.registerReceiver(receiver, f);
                }
                receiverRegistered = true;
            }
            adapter.cancelDiscovery();
            adapter.startDiscovery();
        } catch (Throwable t) {
            emit("bt_error", "Поиск недоступен: " + t.getMessage());
        }
    }

    @UsedByGodot
    public void cancelDiscovery() {
        try {
            if (adapter != null) adapter.cancelDiscovery();
        } catch (Throwable ignored) { }
    }

    // ------------------------------------------------------------------ клиент

    @UsedByGodot
    public void connectTo(final String address) {
        if (adapter == null) {
            emit("bt_connect_failed", "Bluetooth недоступен");
            return;
        }
        new Thread(() -> {
            cancelDiscovery();
            BluetoothSocket s = null;
            try {
                BluetoothDevice dev = adapter.getRemoteDevice(address);
                try {
                    s = dev.createInsecureRfcommSocketToServiceRecord(APP_UUID);
                    s.connect();
                } catch (IOException first) {
                    try { if (s != null) s.close(); } catch (IOException ignored) { }
                    s = dev.createRfcommSocketToServiceRecord(APP_UUID);
                    s.connect();
                }
                startConn(0, s, false);
                emit("bt_connected_to_host");
            } catch (Throwable t) {
                try { if (s != null) s.close(); } catch (IOException ignored) { }
                emit("bt_connect_failed", "Не удалось подключиться. Хост создал игру?");
            }
        }, "DurakBT-connect").start();
    }

    // ------------------------------------------------------------------ обмен

    private void startConn(final int id, BluetoothSocket s, boolean announce) {
        final Conn c = new Conn();
        c.id = id;
        c.socket = s;
        try {
            c.in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            c.out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            String n = null;
            try {
                n = s.getRemoteDevice().getName();
            } catch (Throwable ignored) { }
            c.name = n == null ? "" : n;
        } catch (IOException e) {
            try { s.close(); } catch (IOException ignored) { }
            return;
        }
        conns.put(id, c);
        if (announce) emit("bt_peer_connected", id, c.name);
        Thread reader = new Thread(() -> {
            try {
                while (true) {
                    int len = c.in.readInt();
                    if (len < 0 || len > MAX_FRAME) break;
                    byte[] buf = new byte[len];
                    c.in.readFully(buf);
                    emit("bt_message", id, Base64.encodeToString(buf, Base64.NO_WRAP));
                }
            } catch (IOException ignored) {
            }
            drop(c);
        }, "DurakBT-read-" + id);
        reader.start();
    }

    private void drop(Conn c) {
        if (conns.remove(c.id) != null) {
            try { c.socket.close(); } catch (IOException ignored) { }
            emit("bt_peer_disconnected", c.id);
        }
    }

    @UsedByGodot
    public void send(final int id, final String b64) {
        final Conn c = conns.get(id);
        if (c == null) return;
        final byte[] data;
        try {
            data = Base64.decode(b64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            return;
        }
        writer.execute(() -> {
            try {
                c.out.writeInt(data.length);
                c.out.write(data);
                c.out.flush();
            } catch (IOException e) {
                drop(c);
            }
        });
    }

    @UsedByGodot
    public void disconnectPeer(int id) {
        Conn c = conns.get(id);
        if (c != null) drop(c);
    }

    @UsedByGodot
    public void closeAll() {
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (IOException ignored) { }
            serverSocket = null;
        }
        for (Conn c : conns.values()) {
            conns.remove(c.id);
            try { c.socket.close(); } catch (IOException ignored) { }
        }
        cancelDiscovery();
    }

    // ------------------------------------------------------------------ Wi-Fi

    @UsedByGodot
    public void acquireMulticastLock() {
        try {
            if (multicastLock == null) {
                WifiManager wm = (WifiManager) getGodot().getContext().getApplicationContext()
                        .getSystemService(Context.WIFI_SERVICE);
                if (wm == null) return;
                multicastLock = wm.createMulticastLock("durak-discovery");
                multicastLock.setReferenceCounted(false);
            }
            if (!multicastLock.isHeld()) multicastLock.acquire();
        } catch (Throwable t) {
            Log.w(TAG, "multicast", t);
        }
    }

    @UsedByGodot
    public void releaseMulticastLock() {
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Throwable ignored) { }
    }

    @Override
    public void onMainDestroy() {
        closeAll();
        releaseMulticastLock();
        Activity a = getActivity();
        if (receiverRegistered && a != null) {
            try { a.unregisterReceiver(receiver); } catch (Throwable ignored) { }
        }
        writer.shutdownNow();
        super.onMainDestroy();
    }
}
