package com.localcast.pro.service;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

import com.localcast.pro.core.DeviceManager;
import com.localcast.pro.utils.Logger;

/**
 * 设备发现后台服务
 * 
 * 职责：
 * 1. 在后台持续运行mDNS和UDP广播发现
 * 2. 维护设备列表
 * 3. 即使App在后台也能被发现
 */
public class DiscoveryService extends Service {

    private static final String TAG = "DiscoveryService";

    private DeviceManager deviceManager;

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.i(TAG, "DiscoveryService created");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "START_DISCOVERY".equals(intent.getAction())) {
            startDiscovery();
        } else if (intent != null && "STOP_DISCOVERY".equals(intent.getAction())) {
            stopDiscovery();
            stopSelf();
            return START_NOT_STICKY;
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startDiscovery() {
        if (deviceManager == null) {
            deviceManager = new DeviceManager();
            deviceManager.init(this);
            deviceManager.startDiscovery();
            Logger.i(TAG, "Discovery started in background");
        }
    }

    private void stopDiscovery() {
        if (deviceManager != null) {
            deviceManager.release();
            deviceManager = null;
            Logger.i(TAG, "Discovery stopped");
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopDiscovery();
    }
}
