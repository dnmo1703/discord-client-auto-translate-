package com.denimo.dcclient;

import android.content.Context;
import android.util.Log;

import java.net.InetSocketAddress;
import java.net.Socket;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/** Запускает встроенное ядро xray с локальным HTTP-прокси 127.0.0.1:HTTP_PORT (без VPN, только для этого приложения). */
public final class XrayManager {
    private static final String TAG = "DCXray";
    private static CoreController controller;
    private static boolean envReady;
    private static volatile String lastError;

    private XrayManager() {}

    public static synchronized boolean isRunning() {
        return controller != null && controller.getIsRunning();
    }

    public static String lastError() {
        return lastError;
    }

    /** Блокирующий вызов: стартует ядро и ждёт, пока порт начнёт принимать соединения. */
    public static synchronized boolean start(Context ctx, String configJson) {
        lastError = null;
        try {
            if (!envReady) {
                Libv2ray.initCoreEnv(ctx.getFilesDir().getAbsolutePath(), "");
                envReady = true;
            }
            if (controller == null) {
                controller = Libv2ray.newCoreController(new CoreCallbackHandler() {
                    @Override public long startup() { return 0; }
                    @Override public long shutdown() { return 0; }
                    @Override public long onEmitStatus(long code, String msg) {
                        Log.i(TAG, "status " + code + ": " + msg);
                        return 0;
                    }
                });
            }
            if (controller.getIsRunning()) controller.stopLoop();
            controller.startLoop(configJson, 0); // 0 = без TUN
        } catch (Throwable e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.toString();
            Log.e(TAG, "start failed", e);
            return false;
        }
        for (int i = 0; i < 50; i++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", VlessConfig.HTTP_PORT), 200);
                return true;
            } catch (Exception ignored) {
                try { Thread.sleep(100); } catch (InterruptedException ie) { break; }
            }
        }
        lastError = "Локальный прокси не поднялся";
        return false;
    }

    public static synchronized void stop() {
        try {
            if (controller != null && controller.getIsRunning()) controller.stopLoop();
        } catch (Throwable e) {
            Log.e(TAG, "stop failed", e);
        }
    }

    /** Проверка: задержка до Google через прокси, мс (или -1). */
    public static long measureDelay() {
        try {
            if (controller != null && controller.getIsRunning())
                return controller.measureDelay("https://www.google.com/generate_204");
        } catch (Throwable e) {
            lastError = e.getMessage();
        }
        return -1;
    }
}
