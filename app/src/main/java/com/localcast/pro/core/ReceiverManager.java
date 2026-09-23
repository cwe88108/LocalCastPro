package com.localcast.pro.core;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.localcast.pro.utils.CodecUtils;
import com.localcast.pro.utils.DisplayUtils;
import com.localcast.pro.utils.Logger;

import android.graphics.Point;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 接收端管理器
 *
 * 职责：
 * 1. 从StreamTransport接收帧
 * 2. 视频解码并渲染到Surface
 * 3. 音频解码并播放
 * 4. 性能统计
 */
public class ReceiverManager {

    private static final String TAG = "ReceiverManager";

    public static final int DISPLAY_MODE_FIT = 0;
    /** 等比放大裁剪，保持投屏端比例并铺满屏幕（4K 电视全屏推荐） */
    public static final int DISPLAY_MODE_COVER = 1;
    /** 独立缩放 X/Y，可能变形但绝对铺满 */
    public static final int DISPLAY_MODE_STRETCH = 2;
    public static final int DISPLAY_MODE_ORIGINAL = 3;

    private Context context;
    private ConnectionManager connectionManager;

    private VideoDecoder videoDecoder;
    private AudioDecoder audioDecoder;

    private HandlerThread receiverThread;
    private Handler receiverHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean audioEnabled = new AtomicBoolean(true);
    private volatile boolean decodersStarted = false;

    private SurfaceHolder surfaceHolder;
    private Surface decoderSurface;

    // 视频实际尺寸（0=未知，由解码器 INFO_OUTPUT_FORMAT_CHANGED 得到）
    private int videoWidth = 0;
    private int videoHeight = 0;
    private int displayWidth = 0;
    private int displayHeight = 0;
    private int displayMode = DISPLAY_MODE_FIT;
    private int screenWidth;
    private int screenHeight;

    // 缓存的视频配置帧（SPS/PPS/VPS）。视频解码器尚未创建时，
    // 发送端先到达的 CSD 帧会被暂存，待解码器 start() 后立即重放，
    // 否则解码器永远拿不到码流权威分辨率/参数集（导致比例错乱）。
    private byte[] pendingVideoConfig = null;
    private int pendingVideoConfigLength = 0;
    private long pendingVideoConfigPts = 0;

    private final AtomicLong frameCount = new AtomicLong(0);
    private final AtomicLong totalBytesReceived = new AtomicLong(0);
    private long lastStatsTime = 0;
    private int currentFps = 0;
    private int currentBitrate = 0;

    private OnReceiverStateListener stateListener;

    public interface OnReceiverStateListener {
        void onReceiverReady();
        void onReceiverStopped();
        void onVideoSizeChanged(int width, int height);
        void onDisplaySizeChanged(int displayWidth, int displayHeight);
        void onDisplayModeChanged(int displayMode);
        void onStatsUpdated(int fps, int bitrateKbps, long latencyMs);
        void onError(String error);
    }

    public ReceiverManager(Context context, ConnectionManager connectionManager) {
        this.context = context.getApplicationContext();
        this.connectionManager = connectionManager;
        Point physical = DisplayUtils.getPhysicalScreenSize(context);
        this.screenWidth = physical.x;
        this.screenHeight = physical.y;
        Logger.i(TAG, "Receiver physical screen: " + screenWidth + "x" + screenHeight);
    }

    /**
     * 设置SurfaceHolder
     */
    public void setSurfaceHolder(SurfaceHolder holder) {
        this.surfaceHolder = holder;
        this.decoderSurface = holder.getSurface();

        holder.addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                decoderSurface = holder.getSurface();
                Logger.i(TAG, "Surface created");
                // Surface就绪后启动解码器（处理TCP先于Surface创建的情况）
                if (running.get() && videoDecoder == null) {
                    startVideoDecoder();
                }
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                screenWidth = w;
                screenHeight = h;
                recalculateDisplaySize();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                decoderSurface = null;
            }
        });
    }

    public void setSurface(Surface surface) {
        this.decoderSurface = surface;
    }

    /**
     * 更新 TextureView 实际可见区域尺寸（用于等比缩放/居中计算）。
     * 使用 DisplayMetrics 会与 TextureView 真实尺寸不一致，导致视频偏移。
     */
    public void setViewSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        this.screenWidth = width;
        this.screenHeight = height;
        recalculateDisplaySize();
    }

    /**
     * 启动接收（初始化，等待TCP连接）
     */
    public void startReceiving() {
        if (running.get()) return;

        try {
            receiverThread = new HandlerThread("ReceiverThread",
                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            receiverThread.start();
            receiverHandler = new Handler(receiverThread.getLooper());

            running.set(true);
            lastStatsTime = System.currentTimeMillis();

            Logger.i(TAG, "Receiver initialized, waiting for connection...");

            if (stateListener != null) {
                stateListener.onReceiverReady();
            }
        } catch (Exception e) {
            Logger.e(TAG, "Failed to start receiver", e);
            notifyError("启动接收失败: " + e.getMessage());
        }
    }

    /**
     * 启动解码器（TCP连接建立后调用）
     * 关键修复：始终启动读取循环和心跳，解码器在Surface就绪后启动
     */
    public synchronized void startDecoders() {
        if (!running.get()) {
            Logger.w(TAG, "Receiver not running");
            return;
        }

        // 允许重连：若解码器已启动则先重置
        if (decodersStarted) {
            Logger.i(TAG, "Resetting decoders for new connection");
            resetSession();
        }

        // 获取StreamTransport并注册帧接收回调
        StreamTransport transport = connectionManager.getStreamTransport();
        if (transport == null) {
            Logger.e(TAG, "StreamTransport is null");
            notifyError("传输层未就绪");
            return;
        }

        decodersStarted = true;

        // 用协商尺寸作为初始视频尺寸（解码器 INFO_OUTPUT_FORMAT_CHANGED 之前
        // UI 需要一个合理的尺寸做 transform，否则 videoWidth=0 导致无缩放变换 → 拉伸错乱）
        int negotiatedW = connectionManager.getNegotiatedWidth();
        int negotiatedH = connectionManager.getNegotiatedHeight();
        if (negotiatedW > 0 && negotiatedH > 0) {
            videoWidth = negotiatedW;
            videoHeight = negotiatedH;
            recalculateDisplaySize();
            final int w = videoWidth, h = videoHeight;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (stateListener != null) {
                    stateListener.onVideoSizeChanged(w, h);
                }
            });
        }

        transport.setOnFrameReceivedListener(this::handleFrameReceived);
        transport.setOnErrorListener(error -> {
            Logger.w(TAG, "Stream error: " + error);
            resetSession();
            connectionManager.onTransportFailure(error);
        });

        // 始终启动读取线程（即使Surface未就绪也要读帧更新心跳）
        transport.startReading();

        // The receiver must also send heartbeats. Without this, a sender cannot
        // distinguish a dead receiver from an idle but healthy one.
        connectionManager.startHeartbeat();

        // 启动音频解码器
        if (audioEnabled.get()) {
            Logger.i(TAG, "Attempting to start audio decoder...");
            startAudioDecoder();
        } else {
            Logger.w(TAG, "⚠️ Audio is disabled locally, skipping audio decoder");
        }

        // 尝试启动视频解码器（Surface可能已就绪或稍后就绪）
        if (decoderSurface != null) {
            startVideoDecoder();
            Logger.i(TAG, "Decoders started with surface, receiving frames...");
        } else {
            Logger.i(TAG, "Read loop and heartbeat started, waiting for surface...");
        }
    }

    /**
     * 重置解码会话但保持接收端就绪（等待下一次连接）
     */
    public synchronized void resetSession() {
        decodersStarted = false;
        pendingVideoConfig = null;
        pendingVideoConfigLength = 0;

        final VideoDecoder vDec = videoDecoder;
        final AudioDecoder aDec = audioDecoder;
        videoDecoder = null;
        audioDecoder = null;

        if (vDec != null || aDec != null) {
            new Thread(() -> {
                try {
                    if (vDec != null) vDec.release();
                } catch (Exception e) {
                    Logger.e(TAG, "VideoDecoder release error", e);
                }
                try {
                    if (aDec != null) aDec.release();
                } catch (Exception e) {
                    Logger.e(TAG, "AudioDecoder release error", e);
                }
            }, "ReceiverResetThread").start();
        }
        Logger.i(TAG, "Receiver session reset, waiting for reconnect");
    }

    /**
     * 停止接收（非阻塞，不卡UI线程）
     */
    public void stopReceiving() {
        if (!running.getAndSet(false)) return;
        decodersStarted = false;

        Logger.i(TAG, "Stopping receiver...");

        // 停止处理线程
        if (receiverThread != null) {
            receiverThread.quitSafely();
            receiverThread = null;
        }

        // 将解码器释放移到后台线程（decoder.stop()可能阻塞）
        final VideoDecoder vDec = videoDecoder;
        final AudioDecoder aDec = audioDecoder;
        videoDecoder = null;
        audioDecoder = null;

        new Thread(() -> {
            try {
                if (vDec != null) vDec.release();
            } catch (Exception e) {
                Logger.e(TAG, "VideoDecoder release error", e);
            }
            try {
                if (aDec != null) aDec.release();
            } catch (Exception e) {
                Logger.e(TAG, "AudioDecoder release error", e);
            }
            Logger.i(TAG, "Receiver background cleanup completed");
        }, "ReceiverCleanupThread").start();

        Logger.i(TAG, "Receiver stopped. Frames: " + frameCount.get() +
                ", Bytes: " + totalBytesReceived.get());

        if (stateListener != null) stateListener.onReceiverStopped();
    }

    public void release() {
        stopReceiving();
    }

    public void setDisplayMode(int mode) {
        this.displayMode = mode;
        recalculateDisplaySize();
        
        // 通知UI更新显示模式
        new Handler(Looper.getMainLooper()).post(() -> {
            if (stateListener != null) {
                stateListener.onDisplayModeChanged(mode);
            }
        });
    }

    public int getDisplayMode() { return displayMode; }
    public int getDisplayWidth() { return displayWidth; }
    public int getDisplayHeight() { return displayHeight; }
    public int getVideoWidth() { return videoWidth; }
    public int getVideoHeight() { return videoHeight; }
    public boolean isRunning() { return running.get(); }

    public void setAudioEnabled(boolean enabled) { this.audioEnabled.set(enabled); }
    public int getCurrentFps() { return currentFps; }
    public int getCurrentBitrate() { return currentBitrate; }

    public void setOnReceiverStateListener(OnReceiverStateListener listener) {
        this.stateListener = listener;
    }

    // ==================== 帧接收处理 ====================

    /**
     * 处理接收到的帧（由StreamTransport回调）
     */
    private void handleFrameReceived(byte type, long timestamp, byte flags, byte[] data, int length) {
        if (!running.get()) return;

        // 更新心跳
        connectionManager.updateHeartbeat();

        switch (type) {
            case StreamTransport.TYPE_VIDEO:
                handleVideoFrame(timestamp, flags, data, length);
                break;
            case StreamTransport.TYPE_AUDIO:
                handleAudioFrame(timestamp, data, length);
                break;
            case StreamTransport.TYPE_HEARTBEAT:
                // 心跳已在updateHeartbeat中处理
                break;
            case StreamTransport.TYPE_CONTROL:
                handleControlFrame(data, length);
                break;
        }
    }

    private void handleVideoFrame(long timestamp, byte flags, byte[] data, int length) {
        boolean isKeyFrame = (flags & StreamTransport.FLAG_KEYFRAME) != 0;
        boolean isConfig = (flags & StreamTransport.FLAG_CONFIG) != 0;

        // 关键修复：解码器尚未创建时，CSD(SPS/PPS/VPS) 配置帧必须缓存，
        // 待解码器 start() 后立即重放。否则解码器永远拿不到码流权威分辨率，
        // 只能用协商值（可能与实际编码尺寸不一致）→ 比例错乱。
        if (videoDecoder == null || !videoDecoder.isRunning()) {
            if (isConfig && data != null && length > 0) {
                pendingVideoConfig = new byte[length];
                System.arraycopy(data, 0, pendingVideoConfig, 0, length);
                pendingVideoConfigLength = length;
                pendingVideoConfigPts = timestamp;
                Logger.i(TAG, "Video decoder not ready, cached CSD config frame (" + 
                         length + " bytes, pts=" + timestamp + ")");
            } else {
                Logger.w(TAG, "Dropping video frame before decoder ready: isConfig=" + 
                         isConfig + ", length=" + length);
            }
            return;
        }

        // 喂数据给解码器
        videoDecoder.queueInput(data, length, timestamp, isKeyFrame, isConfig);

        // 更新统计
        frameCount.incrementAndGet();
        totalBytesReceived.addAndGet(length);

        // 定期更新统计
        if (frameCount.get() % 60 == 0) {
            updateStats();
        }
    }

    private void handleAudioFrame(long timestamp, byte[] data, int length) {
        if (audioDecoder == null) {
            Logger.w(TAG, "⚠️ Received audio frame but decoder is NULL");
            return;
        }
        if (!audioDecoder.isRunning()) {
            Logger.w(TAG, "⚠️ Received audio frame but decoder is NOT RUNNING");
            return;
        }
        
        Logger.d(TAG, "📥 Received audio frame: length=" + length + ", pts=" + timestamp);
        audioDecoder.queueInput(data, 0, length, timestamp);
    }

    /**
     * 处理控制命令帧
     * command_type: 1=fullscreen_toggle
     */
    private void handleControlFrame(byte[] data, int length) {
        if (data == null || length < 1) {
            Logger.w(TAG, "Invalid control frame");
            return;
        }

        byte commandType = data[0];
        Logger.i(TAG, "Received control command: type=" + commandType);

        switch (commandType) {
            case 1: // fullscreen_enter: 保持投屏端比例铺满 4K/全屏
                setDisplayMode(DISPLAY_MODE_COVER);
                Logger.i(TAG, "Remote fullscreen -> cover mode");
                break;
            case 2: // fullscreen_exit
                setDisplayMode(DISPLAY_MODE_FIT);
                Logger.i(TAG, "Remote fullscreen exit -> fit mode");
                break;
            default:
                Logger.w(TAG, "Unknown control command type: " + commandType);
                break;
        }
    }

    // ==================== 解码器 ====================

    private void startVideoDecoder() {
        if (decoderSurface == null) {
            Logger.e(TAG, "No surface for video decoder");
            return;
        }

        String codecMime = connectionManager.getNegotiatedCodec() != null &&
                connectionManager.getNegotiatedCodec().equals("hevc")
                ? CodecUtils.MIME_HEVC : CodecUtils.MIME_AVC;

        // 获取协商的分辨率
        int width = connectionManager.getNegotiatedWidth();
        int height = connectionManager.getNegotiatedHeight();

        try {
            videoDecoder = new VideoDecoder();
            videoDecoder.setOnDecoderReadyListener((w, h) -> {
                videoWidth = w;
                videoHeight = h;
                recalculateDisplaySize();

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (stateListener != null) {
                        stateListener.onVideoSizeChanged(w, h);
                    }
                });
            });
            videoDecoder.setOnErrorListener(e -> notifyError("视频解码错误: " + 
                (e != null ? e.getMessage() : "未知错误")));

            if (!videoDecoder.configure(codecMime, decoderSurface, width, height, pendingVideoConfig, pendingVideoConfigLength)) {
                notifyError("视频解码器配置失败: " + codecMime + " " + width + "x" + height);
                return;
            }

            videoDecoder.start();
            Logger.i(TAG, "Video decoder started: " + codecMime + " " + width + "x" + height +
                    (pendingVideoConfig != null ? " (with cached csd-0)" : ""));

            // CSD 已在 configure() 阶段作为 csd-0 注入解码器，这里释放缓存即可
            if (pendingVideoConfig != null) {
                pendingVideoConfig = null;
                pendingVideoConfigLength = 0;
            }
        } catch (Exception e) {
            Logger.e(TAG, "Failed to start video decoder", e);
            notifyError("视频解码器启动失败: " + (e != null ? e.getMessage() : "未知错误"));
        }
    }

    private void startAudioDecoder() {
        try {
            Logger.i(TAG, "Creating audio decoder...");
            audioDecoder = new AudioDecoder();
            audioDecoder.setOnErrorListener(e -> Logger.e(TAG, "❌ Audio decoder error", e));

            if (!audioDecoder.start()) {
                Logger.e(TAG, "❌ Audio decoder start FAILED");
                audioEnabled.set(false);
                audioDecoder = null;
            } else {
                Logger.i(TAG, "✅ Audio decoder started successfully");
            }
        } catch (Exception e) {
            Logger.e(TAG, "❌ Failed to create audio decoder", e);
            audioEnabled.set(false);
            audioDecoder = null;
        }
    }

    // ==================== 显示尺寸 ====================

    public void recalculateDisplaySize() {
        if (videoWidth <= 0 || videoHeight <= 0) return;
        if (screenWidth <= 0 || screenHeight <= 0) return;

        switch (displayMode) {
            case DISPLAY_MODE_COVER:
            case DISPLAY_MODE_STRETCH:
                displayWidth = screenWidth;
                displayHeight = screenHeight;
                break;
            case DISPLAY_MODE_ORIGINAL:
                displayWidth = videoWidth;
                displayHeight = videoHeight;
                break;
            case DISPLAY_MODE_FIT:
            default:
                float videoRatio = (float) videoWidth / videoHeight;
                float screenRatio = (float) screenWidth / screenHeight;
                if (videoRatio > screenRatio) {
                    displayWidth = screenWidth;
                    displayHeight = (int) (screenWidth / videoRatio);
                } else {
                    displayHeight = screenHeight;
                    displayWidth = (int) (screenHeight * videoRatio);
                }
                break;
        }

        new Handler(Looper.getMainLooper()).post(() -> {
            if (stateListener != null) {
                stateListener.onDisplaySizeChanged(displayWidth, displayHeight);
            }
        });
    }

    // ==================== 统计 ====================

    private void updateStats() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastStatsTime;

        if (elapsed >= 1000) {
            long frames = frameCount.get();
            long bytes = totalBytesReceived.get();

            int negotiatedFps = connectionManager.getNegotiatedFps();
            currentFps = Math.min((int) (frames * 1000.0 / elapsed),
                    negotiatedFps > 0 ? negotiatedFps : 30);
            currentBitrate = (int) (bytes * 8.0 / elapsed);

            frameCount.set(0);
            totalBytesReceived.set(0);
            lastStatsTime = now;

            if (stateListener != null) {
                stateListener.onStatsUpdated(currentFps, currentBitrate / 1000, 0);
            }
        }
    }

    // ==================== 辅助方法 ====================

    private void notifyError(String error) {
        Logger.e(TAG, error);
        new Handler(Looper.getMainLooper()).post(() -> {
            if (stateListener != null) stateListener.onError(error);
        });
    }
}
