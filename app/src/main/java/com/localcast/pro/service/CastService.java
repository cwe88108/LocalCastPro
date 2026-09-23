package com.localcast.pro.service;

import android.app.Notification;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.localcast.pro.LocalCastApplication;
import com.localcast.pro.R;
import com.localcast.pro.ui.MainActivity;
import com.localcast.pro.utils.Logger;

/**
 * 投屏前台服务
 *
 * Android 14+ 要求：
 * 1. 先弹出 MediaProjection 授权并完成用户确认
 * 2. 再 startForeground(type=mediaProjection)
 * 3. 最后调用 getMediaProjection()
 */
public class CastService extends Service {

    private static final String TAG = "CastService";
    private static final int NOTIFICATION_ID = 1001;
    private static final long WAKE_LOCK_TIMEOUT_MS = 15 * 60 * 1000L;
    private static final long WAKE_LOCK_RENEWAL_MS = 10 * 60 * 1000L;

    public static final String ACTION_START_CAST = "START_CAST";
    public static final String ACTION_STOP_CAST = "STOP_CAST";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    public interface OnProjectionReadyListener {
        void onProjectionReady(MediaProjection projection);
        void onProjectionFailed(String error);
    }

    private static volatile OnProjectionReadyListener projectionReadyListener;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isCasting = false;
    private String statusText = "就绪";
    private PowerManager.WakeLock castWakeLock;
    private final Runnable wakeLockRenewal = new Runnable() {
        @Override public void run() {
            if (!isCasting) return;
            releaseCastWakeLock();
            acquireCastWakeLock();
        }
    };

    public static void setProjectionReadyListener(@Nullable OnProjectionReadyListener listener) {
        projectionReadyListener = listener;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP_CAST.equals(action)) {
            LocalCastApplication app = (LocalCastApplication) getApplication();
            if (app.getCastManager() != null) {
                app.getCastManager().stopCurrentModeFromService();
            }
            isCasting = false;
            statusText = "已停止";
            releaseCastWakeLock();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (!ACTION_START_CAST.equals(action)) {
            return START_NOT_STICKY;
        }

        isCasting = true;
        statusText = "投屏中…";

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = getResultDataExtra(intent);
        if (resultCode == 0 || resultData == null) {
            notifyProjectionFailed("缺少屏幕录制授权数据，请重试");
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        try {
            promoteToForeground();
            acquireCastWakeLock();
        } catch (SecurityException e) {
            Logger.e(TAG, "startForeground failed", e);
            notifyProjectionFailed("前台服务启动失败，请确认已授予屏幕录制权限后重试");
            stopSelfSafely();
            return START_NOT_STICKY;
        }

        obtainMediaProjection(resultCode, resultData, 0);
        return START_STICKY;
    }

    private void promoteToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, createNotification());
        }
    }

    private void obtainMediaProjection(int resultCode, Intent resultData, int attempt) {
        MediaProjectionManager pm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (pm == null) {
            notifyProjectionFailed("设备不支持屏幕录制");
            stopSelfSafely();
            return;
        }

        try {
            MediaProjection projection = pm.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                notifyProjectionFailed("无法获取屏幕录制权限");
                stopSelfSafely();
                return;
            }
            OnProjectionReadyListener listener = projectionReadyListener;
            if (listener != null) {
                mainHandler.post(() -> listener.onProjectionReady(projection));
            } else {
                Logger.w(TAG, "No projection listener; releasing MediaProjection");
                projection.stop();
                stopSelfSafely();
            }
        } catch (SecurityException e) {
            if (attempt < 5) {
                long delayMs = 100L * (attempt + 1);
                Logger.w(TAG, "getMediaProjection retry " + (attempt + 1) + " after " + delayMs + "ms");
                mainHandler.postDelayed(
                        () -> obtainMediaProjection(resultCode, resultData, attempt + 1), delayMs);
            } else {
                Logger.e(TAG, "getMediaProjection failed after retries", e);
                notifyProjectionFailed("屏幕录制权限获取失败，请重试");
                stopSelfSafely();
            }
        } catch (Exception e) {
            Logger.e(TAG, "getMediaProjection failed", e);
            notifyProjectionFailed("投屏启动失败: " + e.getMessage());
            stopSelfSafely();
        }
    }

    @Nullable
    private Intent getResultDataExtra(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        }
        return intent.getParcelableExtra(EXTRA_RESULT_DATA);
    }

    private void notifyProjectionFailed(String error) {
        OnProjectionReadyListener listener = projectionReadyListener;
        if (listener != null) {
            mainHandler.post(() -> listener.onProjectionFailed(error));
        }
    }

    private void stopSelfSafely() {
        isCasting = false;
        releaseCastWakeLock();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void acquireCastWakeLock() {
        if (castWakeLock != null && castWakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        castWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalCastPro:Cast");
        castWakeLock.setReferenceCounted(false);
        castWakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
        mainHandler.postDelayed(wakeLockRenewal, WAKE_LOCK_RENEWAL_MS);
        Logger.i(TAG, "Cast wake lock acquired");
    }

    private void releaseCastWakeLock() {
        mainHandler.removeCallbacks(wakeLockRenewal);
        if (castWakeLock != null && castWakeLock.isHeld()) {
            castWakeLock.release();
            Logger.i(TAG, "Cast wake lock released");
        }
        castWakeLock = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("MissingPermission") // Guarded by canPostNotifications().
    public void updateStatus(String status) {
        this.statusText = status;
        if (canPostNotifications()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, createNotification());
        }
    }

    private boolean canPostNotifications() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, CastService.class);
        stopIntent.setAction(ACTION_STOP_CAST);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(
                this, LocalCastApplication.getNotificationChannelId())
                .setContentTitle("投屏神器")
                .setContentText(statusText)
                .setSmallIcon(android.R.drawable.ic_menu_slideshow)
                .setContentIntent(pendingIntent)
                .setOngoing(isCasting)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_media_pause, "停止", stopPendingIntent);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }

        return builder.build();
    }

    @Override
    public void onDestroy() {
        releaseCastWakeLock();
        super.onDestroy();
        isCasting = false;
        projectionReadyListener = null;
    }
}
