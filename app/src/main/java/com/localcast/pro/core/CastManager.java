package com.localcast.pro.core;

import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjection;
import android.view.Surface;

import java.util.concurrent.atomic.AtomicBoolean;

import com.localcast.pro.service.CastService;
import com.localcast.pro.utils.DisplayUtils;
import com.localcast.pro.utils.Logger;
import com.localcast.pro.utils.NetworkUtils;

/**
 * 投屏管理器 - 统一管理投屏全生命周期
 *
 * 架构（参考Scrcpy）：
 * 1. 接收端作为TCP Server监听连接
 * 2. 发送端作为TCP Client连接接收端
 * 3. 握手完成后，通过StreamTransport传输音视频帧
 */
public class CastManager {

    private static final String TAG = "CastManager";

    public enum CastMode { IDLE, SENDING, RECEIVING }

    private Context context;
    private volatile CastMode currentMode = CastMode.IDLE;

    private DeviceInfo localDevice;
    private ConnectionManager connectionManager;
    private SenderManager senderManager;
    private ReceiverManager receiverManager;

    private MediaProjection pendingProjection;
    private MediaProjection sessionProjection;
    private String senderTargetIp;
    private boolean senderHasConnected;
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private int reconnectAttempts;

    private OnCastStateListener castStateListener;

    public interface OnCastStateListener {
        void onModeChanged(CastMode mode);
        void onConnected(DeviceInfo remoteDevice);
        void onDisconnected(String reason);
        void onSenderStats(int fps, int bitrateKbps, long latencyMs);
        void onReceiverStats(int fps, int bitrateKbps, long latencyMs);
        void onVideoSizeChanged(int width, int height);
        void onDisplaySizeChanged(int displayWidth, int displayHeight);
        void onDisplayModeChanged(int displayMode);
        void onError(String error);
    }

    // ==================== 初始化 ====================

    public void init(Context context) {
        this.context = context.getApplicationContext();

        localDevice = new DeviceInfo();
        localDevice.initDeviceId(context);
        localDevice.setIpAddress(NetworkUtils.getLocalIpAddress());
        localDevice.setDeviceType(DeviceInfo.isTvDevice(context)
                ? DeviceInfo.TYPE_TV : DeviceInfo.TYPE_PHONE);
        // This value is informational for discovery only. Session negotiation
        // deliberately uses AVC until bidirectional codec renegotiation exists.
        localDevice.setHevcSupported(
                com.localcast.pro.utils.CodecUtils.isHevcEncoderSupported()
                        || com.localcast.pro.utils.CodecUtils.isHevcDecoderSupported());

        // 使用物理屏尺寸（4K 电视 DisplayMetrics 可能仅为 1080p UI 模式）
        android.graphics.Point realSize = DisplayUtils.getPhysicalScreenSize(context);
        int screenW = realSize.x;
        int screenH = realSize.y;
        localDevice.setScreenWidth(screenW);
        localDevice.setScreenHeight(screenH);
        localDevice.setTcpPort(ConnectionManager.TCP_PORT);
        localDevice.setUdpPort(ConnectionManager.TCP_PORT);

        connectionManager = new ConnectionManager();
        connectionManager.init(localDevice);

        Logger.i(TAG, "CastManager initialized: " + localDevice.getDeviceName() +
                " @ " + localDevice.getIpAddress());
    }

    // ==================== 发送端 ====================

    public void startAsSender(String targetIp, MediaProjection projection) {
        if (currentMode != CastMode.IDLE) {
            Logger.w(TAG, "Already in mode: " + currentMode);
            return;
        }
        currentMode = CastMode.SENDING;
        pendingProjection = projection;
        senderTargetIp = targetIp;
        senderHasConnected = false;
        reconnectAttempts = 0;
        reconnecting.set(false);

        String fpsPreference = androidx.preference.PreferenceManager
                .getDefaultSharedPreferences(context).getString("frame_rate", "30");
        try {
            connectionManager.setRequestedFps(Integer.parseInt(fpsPreference));
        } catch (NumberFormatException ignored) {
            connectionManager.setRequestedFps(30);
        }

        Logger.i(TAG, "Starting as sender, connecting to: " + targetIp);

        // 创建发送端管理器
        senderManager = new SenderManager(context, connectionManager);
        senderManager.setOnSenderStateListener(new SenderManager.OnSenderStateListener() {
            @Override
            public void onSenderStarted() {
                Logger.i(TAG, "Sender started - encoding active");
            }

            @Override
            public void onSenderStopped() {
                Logger.i(TAG, "Sender stopped");
            }

            @Override
            public void onStatsUpdated(int fps, int kbps, long ms) {
                if (castStateListener != null) {
                    castStateListener.onSenderStats(fps, kbps, ms);
                }
            }

            @Override
            public void onError(String e) {
                Logger.e(TAG, "Sender error: " + e);
                if (castStateListener != null) castStateListener.onError(e);
            }
        });

        // 设置连接回调
        connectionManager.setOnConnectionStateListener(new ConnectionManager.OnConnectionStateListener() {
            @Override
            public void onDeviceConnected(DeviceInfo remote) {
                Logger.i(TAG, "Connected to receiver: " + remote.getDeviceName());
                Logger.i(TAG, "Codec: " + connectionManager.getNegotiatedCodec() +
                        ", Resolution: " + connectionManager.getNegotiatedWidth() +
                        "x" + connectionManager.getNegotiatedHeight());

                StreamTransport transport = connectionManager.getStreamTransport();
                if (transport != null) {
                    // A sender must read the receiver's heartbeats as well; the
                    // former implementation only wrote video and could not detect
                    // a half-open receiver connection.
                    transport.setOnFrameReceivedListener((type, timestamp, flags, data, length) ->
                            connectionManager.updateHeartbeat());
                    transport.setOnErrorListener(connectionManager::onTransportFailure);
                    transport.startReading();
                }

                // A reconnect keeps the existing MediaProjection and VirtualDisplay.
                // Android 14 rejects a second createVirtualDisplay on one token.
                MediaProjection projection = pendingProjection != null
                        ? pendingProjection : sessionProjection;
                if (senderManager != null && projection != null) {
                    if (senderManager.isCaptureActive()) {
                        Logger.i(TAG, "Transport restored; retaining existing capture");
                        senderManager.onTransportReconnected();
                    } else {
                        Logger.i(TAG, "Starting video encoder...");
                        senderManager.startCasting(projection);
                    }
                    sessionProjection = projection;
                    pendingProjection = null;
                    senderHasConnected = true;
                    reconnecting.set(false);
                    reconnectAttempts = 0;

                    // 启动心跳
                    connectionManager.startHeartbeat();
                }

                if (castStateListener != null) castStateListener.onConnected(remote);
            }

            @Override
            public void onDeviceDisconnected(String reason) {
                Logger.w(TAG, "Disconnected: " + reason);
                if (scheduleSenderReconnect(reason)) {
                    if (castStateListener != null) {
                        castStateListener.onError("连接中断，正在自动重连…");
                    }
                    return;
                }
                // 直接清理资源，避免循环调用
                if (senderManager != null) {
                    senderManager.stopCasting();
                    senderManager = null;
                }
                if (pendingProjection != null) {
                    try { pendingProjection.stop(); } catch (Exception ignored) {}
                    pendingProjection = null;
                }
                sessionProjection = null;
                senderHasConnected = false;
                reconnecting.set(false);
                if (receiverManager != null) {
                    receiverManager.release();
                    receiverManager = null;
                }
                currentMode = CastMode.IDLE;
                stopCastService();
                if (castStateListener != null) {
                    castStateListener.onDisconnected(reason);
                    castStateListener.onModeChanged(CastMode.IDLE);
                }
            }
        });

        // 发起TCP连接
        connectionManager.connectTo(targetIp);

        if (castStateListener != null) castStateListener.onModeChanged(CastMode.SENDING);
    }

    /**
     * Reconnect a sender without consuming its MediaProjection token. A failed
     * TCP retry is reported through the same disconnect callback, so every
     * callback schedules exactly one later attempt until the bounded limit.
     */
    private synchronized boolean scheduleSenderReconnect(String reason) {
        if (currentMode != CastMode.SENDING || !senderHasConnected
                || sessionProjection == null || senderTargetIp == null) {
            return false;
        }
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Logger.e(TAG, "Reconnect limit reached after " + reconnectAttempts
                    + " attempts: " + reason);
            reconnecting.set(false);
            return false;
        }

        if (senderManager == null || !senderManager.isCaptureActive()) return false;
        reconnecting.set(true);
        final int attempt = ++reconnectAttempts;
        final String targetIp = senderTargetIp;
        final long delayMs = Math.min(5000L, 1500L * attempt);
        Logger.i(TAG, "Scheduling reconnect " + attempt + "/" + MAX_RECONNECT_ATTEMPTS
                + " to " + targetIp + " in " + delayMs + "ms");
        new Thread(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            if (currentMode == CastMode.SENDING && reconnecting.get()
                    && targetIp.equals(senderTargetIp)) {
                connectionManager.connectTo(targetIp);
            }
        }, "CastReconnect").start();
        return true;
    }

    // ==================== 接收端 ====================

    public void ensureReceiverReady() {
        if (currentMode == CastMode.SENDING) {
            Logger.w(TAG, "Cannot prepare receiver while sending");
            return;
        }
        if (currentMode == CastMode.RECEIVING && receiverManager != null) {
            if (!connectionManager.isServerRunning()) {
                connectionManager.startAsServer();
            }
            return;
        }
        setupReceiver(null);
    }

    public void startAsReceiver(android.view.Surface surface) {
        if (currentMode == CastMode.SENDING) {
            Logger.w(TAG, "Already in sending mode");
            return;
        }
        if (currentMode != CastMode.RECEIVING || receiverManager == null) {
            setupReceiver(surface);
        } else {
            attachReceiverSurface(surface);
        }
    }

    private void attachReceiverSurface(android.view.Surface surface) {
        if (receiverManager == null) return;
        receiverManager.setSurface(surface);
        if (connectionManager.getState() == ConnectionManager.ConnectionState.CONNECTED) {
            receiverManager.startDecoders();
        }
    }

    private void setupReceiver(android.view.Surface surface) {
        currentMode = CastMode.RECEIVING;

        Logger.i(TAG, "Starting as receiver, waiting for sender...");

        receiverManager = new ReceiverManager(context, connectionManager);
        if (surface != null) {
            receiverManager.setSurface(surface);
        }
        receiverManager.setOnReceiverStateListener(new ReceiverManager.OnReceiverStateListener() {
            @Override
            public void onReceiverReady() {
                Logger.i(TAG, "Receiver ready");
            }

            @Override
            public void onReceiverStopped() {
                Logger.i(TAG, "Receiver stopped");
            }

            @Override
            public void onVideoSizeChanged(int w, int h) {
                Logger.i(TAG, "Video size: " + w + "x" + h);
                if (castStateListener != null) castStateListener.onVideoSizeChanged(w, h);
            }

            @Override
            public void onDisplaySizeChanged(int w, int h) {
                if (castStateListener != null) castStateListener.onDisplaySizeChanged(w, h);
            }

            @Override
            public void onDisplayModeChanged(int mode) {
                if (castStateListener != null) castStateListener.onDisplayModeChanged(mode);
            }

            @Override
            public void onStatsUpdated(int fps, int kbps, long ms) {
                if (castStateListener != null) castStateListener.onReceiverStats(fps, kbps, ms);
            }

            @Override
            public void onError(String e) {
                Logger.e(TAG, "Receiver error: " + e);
                if (castStateListener != null) castStateListener.onError(e);
            }
        });
        receiverManager.startReceiving();

        connectionManager.setOnConnectionStateListener(new ConnectionManager.OnConnectionStateListener() {
            @Override
            public void onDeviceConnected(DeviceInfo remote) {
                Logger.i(TAG, "Sender connected: " + remote.getDeviceName());
                Logger.i(TAG, "Codec: " + connectionManager.getNegotiatedCodec() +
                        ", Resolution: " + connectionManager.getNegotiatedWidth() +
                        "x" + connectionManager.getNegotiatedHeight());

                if (receiverManager != null) {
                    Logger.i(TAG, "Starting decoders...");
                    receiverManager.startDecoders();
                }

                if (castStateListener != null) castStateListener.onConnected(remote);
            }

            @Override
            public void onDeviceDisconnected(String reason) {
                Logger.w(TAG, "Sender disconnected: " + reason);
                if (receiverManager != null) {
                    receiverManager.resetSession();
                }
                connectionManager.resetClientSession();
                if (castStateListener != null) {
                    castStateListener.onDisconnected(reason);
                }
            }
        });

        connectionManager.startAsServer();

        if (castStateListener != null) castStateListener.onModeChanged(CastMode.RECEIVING);
    }

    public void stopCurrentMode() {
        stopCurrentModeInternal(true);
    }

    /** Called by the foreground-service notification action. */
    public void stopCurrentModeFromService() {
        stopCurrentModeInternal(false);
    }

    private void stopCurrentModeInternal(boolean stopService) {
        Logger.i(TAG, "Stopping mode: " + currentMode);
        CastMode modeBeforeStop = currentMode;

        if (senderManager != null) {
            try { senderManager.stopCasting(); } catch (Exception e) { Logger.e(TAG, "Stop sender error", e); }
            senderManager = null;
        }
        if (receiverManager != null) {
            try { receiverManager.release(); } catch (Exception e) { Logger.e(TAG, "Stop receiver error", e); }
            receiverManager = null;
        }

        // 断开连接（不触发回调，避免循环）
        try { connectionManager.disconnectSilently(); } catch (Exception e) { Logger.e(TAG, "Disconnect error", e); }

        currentMode = CastMode.IDLE;
        pendingProjection = null;
        sessionProjection = null;
        senderTargetIp = null;
        senderHasConnected = false;
        reconnecting.set(false);
        if (stopService && modeBeforeStop == CastMode.SENDING) {
            stopCastService();
        }
        if (castStateListener != null) {
            castStateListener.onModeChanged(CastMode.IDLE);
        }
    }

    private void stopCastService() {
        if (context == null) return;
        // The mode has already been torn down. Starting the service with a
        // delayed STOP action could accidentally stop a newly opened session.
        context.stopService(new Intent(context, CastService.class));
    }

    public void release() {
        stopCurrentMode();
        connectionManager.release();
    }

    // ==================== 便捷方法 ====================

    public void pauseCasting() {
        if (senderManager != null) senderManager.pauseCasting();
    }

    public void resumeCasting() {
        if (senderManager != null) senderManager.resumeCasting();
    }

    /** 发送端全屏：切换横屏捕获分辨率以匹配电视 */
    public void setSenderFullscreenCapture(boolean fullscreen) {
        if (senderManager != null) senderManager.setFullscreenCapture(fullscreen);
    }

    public void setDisplayMode(int mode) {
        if (receiverManager != null) receiverManager.setDisplayMode(mode);
    }

    public int getReceiverVideoWidth() {
        return receiverManager != null ? receiverManager.getVideoWidth() : 0;
    }

    public int getReceiverVideoHeight() {
        return receiverManager != null ? receiverManager.getVideoHeight() : 0;
    }

    // ==================== Getters ====================

    public CastMode getCurrentMode() { return currentMode; }
    public DeviceInfo getLocalDevice() { return localDevice; }
    public ConnectionManager getConnectionManager() { return connectionManager; }
    public SenderManager getSenderManager() { return senderManager; }
    public ReceiverManager getReceiverManager() { return receiverManager; }

    public void setOnCastStateListener(OnCastStateListener l) {
        this.castStateListener = l;
    }
}
