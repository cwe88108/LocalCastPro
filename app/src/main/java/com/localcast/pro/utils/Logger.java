package com.localcast.pro.utils;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 日志系统
 * 支持 Logcat 输出和文件记录
 */
public class Logger {

    private static final String TAG = "LocalCastPro";
    private static boolean debugMode = true;
    private static boolean fileLogEnabled = false;
    private static File logFile;

    public static void init(Context context) {
        if (fileLogEnabled) {
            logFile = new File(context.getExternalFilesDir(null), "localcast_log.txt");
        }
    }

    public static void setDebugMode(boolean enabled) {
        debugMode = enabled;
    }

    public static void v(String tag, String msg) {
        if (debugMode) Log.v(TAG + "/" + tag, msg);
        writeToFile("V", tag, msg);
    }

    public static void d(String tag, String msg) {
        if (debugMode) Log.d(TAG + "/" + tag, msg);
        writeToFile("D", tag, msg);
    }

    public static void i(String tag, String msg) {
        if (debugMode) Log.i(TAG + "/" + tag, msg);
        writeToFile("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        if (debugMode) Log.w(TAG + "/" + tag, msg);
        writeToFile("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        if (debugMode) Log.e(TAG + "/" + tag, msg);
        writeToFile("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        if (debugMode) Log.e(TAG + "/" + tag, msg, t);
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        writeToFile("E", tag, msg + "\n" + sw.toString());
    }

    private static void writeToFile(String level, String tag, String msg) {
        if (!fileLogEnabled || logFile == null) return;
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault());
            String timestamp = sdf.format(new Date());
            String line = String.format("[%s] %s/%s: %s\n", timestamp, level, tag, msg);

            FileWriter fw = new FileWriter(logFile, true);
            fw.write(line);
            fw.close();
        } catch (IOException ignored) {
        }
    }
}
