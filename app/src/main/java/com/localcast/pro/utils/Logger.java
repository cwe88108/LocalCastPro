package com.localcast.pro.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

/**
 * Debug-only logs. Release builds never write connection details to Logcat or files.
 */
public class Logger {

    private static final String TAG = "LocalCastPro";
    private static volatile boolean debuggable;
    private static volatile boolean debugMode;

    public static void init(Context context) {
        debuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        debugMode = debuggable;
    }

    public static void setDebugMode(boolean enabled) {
        debugMode = debuggable && enabled;
    }

    public static void v(String tag, String msg) {
        if (debugMode) Log.v(TAG + "/" + tag, msg);
    }

    public static void d(String tag, String msg) {
        if (debugMode) Log.d(TAG + "/" + tag, msg);
    }

    public static void i(String tag, String msg) {
        if (debugMode) Log.i(TAG + "/" + tag, msg);
    }

    public static void w(String tag, String msg) {
        if (debugMode) Log.w(TAG + "/" + tag, msg);
    }

    public static void e(String tag, String msg) {
        if (debugMode) Log.e(TAG + "/" + tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        if (debugMode) Log.e(TAG + "/" + tag, msg, t);
    }
}
