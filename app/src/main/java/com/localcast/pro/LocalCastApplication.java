package com.localcast.pro;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.PreferenceManager;

import com.localcast.pro.core.CastManager;
import com.localcast.pro.utils.Logger;

/**
 * 投屏神器 Application
 * 负责全局初始化：日志系统、通知渠道、CastManager全局持有
 */
public class LocalCastApplication extends Application {

    private static final String CHANNEL_ID = "localcast_cast";
    private static final String CHANNEL_NAME = "投屏服务";
    private static final String CHANNEL_DESC = "投屏神器前台服务通知";

    private static LocalCastApplication instance;
    private static Handler mainHandler;
    private CastManager castManager;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());

        Logger.init(this);
        migrateAudioModeDefault();
        createNotificationChannel();

        Logger.i("LocalCastPro", "Application initialized");
    }

    public static LocalCastApplication getInstance() {
        return instance;
    }

    public static Handler getMainHandler() {
        return mainHandler;
    }

    public CastManager getCastManager() {
        return castManager;
    }

    public void setCastManager(CastManager castManager) {
        this.castManager = castManager;
    }

    /** 1.0.8 的设置页把麦克风作为默认值持久化，导致设备播放声无法投送。 */
    private void migrateAudioModeDefault() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        if (prefs.getBoolean("audio_mode_default_migrated", false)) return;
        SharedPreferences.Editor editor = prefs.edit();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && !prefs.getBoolean("audio_mode_user_selected", false)
                && "microphone".equals(prefs.getString("audio_mode", null))) {
            // 旧版本没有记录「用户主动选择」；无法区分它与旧默认值。
            // 迁移到投屏所需的系统声音，之后在设置中主动选择的麦克风不会再被改动。
            editor.putString("audio_mode", "system");
            Logger.i("LocalCastPro", "Migrated legacy microphone audio default to system audio");
        }
        editor.putBoolean("audio_mode_default_migrated", true).apply();
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(CHANNEL_DESC);
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public static String getNotificationChannelId() {
        return CHANNEL_ID;
    }
}
