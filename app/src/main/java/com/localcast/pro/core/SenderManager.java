package com.localcast.pro.core;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Display;
import android.view.Surface;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.localcast.pro.utils.CodecUtils;
import com.localcast.pro.utils.Logger;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 发送端管理器
 *
 * 职责：
 * 1. MediaProjection 屏幕采集
 * 2. 视频编码（通过StreamTransport发送）
 * 3. 音频编码（通过StreamTransport发送）
 * 4. 性能统计
 */
public class SenderManager {

    private static final String TAG = "SenderManager";

    private Context context;
    private ConnectionManager connectionManager;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;

    private VideoEncoder videoEncoder;
    private AudioEncoder audioEncoder;

    private HandlerThread senderThread;
    private Handler senderHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean audioEnabled = new AtomicBoolean(true);
    private final AtomicBoolean capturePaused = new AtomicBoolean(false);
    private volatile boolean userInitiatedStop = false;
    private BroadcastReceiver screenStateReceiver;
    private MediaProjection.Callback projectionCallback;

    // 性能统计
    private final AtomicLong frameCount = new AtomicLong(0);
    private final AtomicLong totalBytesSent = new AtomicLong(0);
    private long lastStatsTime = 0;
    private int currentFps = 0;
    private int currentBitrate = 0;

    // 编码参数
    private int encodeWidth;
    private int encodeHeight;
    private int encodeBitrate;
    private int encodeFps;
    private String codecMime;
    private volatile byte[] latestVideoConfig;

    private OnSenderStateListener stateListener;

    public interface OnSenderStateListener {
        void onSenderStarted();
        void onSenderStopped();
        void onStatsUpdated(int fps, int bitrateKbps, long latencyMs);
        void onError(String error);
    }

    public SenderManager(Context context, ConnectionManager connectionManager) {
        this.context = context.getApplicationContext();
        this.connectionManager = connectionManager;
    }

    /**
     * 启动投屏发送
     */
    public void startCasting(MediaProjection projection) {
        if (running.get()) {
            Logger.w(TAG, "Already casting");
            return;
        }

        if (projection == null) {
            notifyError("MediaProjection为空");
            return;
        }

        try {
            userInitiatedStop = false;
            capturePaused.set(false);
            mediaProjection = projection;

            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    Logger.w(TAG, "MediaProjection onStop (userStop=" + userInitiatedStop + ")");
                    if (userInitiatedStop) {
                        stopCasting();
                    } else {
                        // onStop revokes the token. Android 14 never permits a
                        // new VirtualDisplay from this MediaProjection.
                        stopCasting();
                        connectionManager.onTransportFailure("屏幕采集授权已结束，请重新发起投屏");
                    }
                }
            };
            mediaProjection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));

            registerScreenStateReceiver();

            // 获取协商的编码参数
            prepareEncodingParams();

            // 创建线程
            senderThread = new HandlerThread("SenderThread",
                    android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            senderThread.start();
            senderHandler = new Handler(senderThread.getLooper());

            running.set(true);
            lastStatsTime = System.currentTimeMillis();

            // 初始化编码器
            senderHandler.post(() -> {
                try {
                    setupVideoEncoder();
                    if (!isCaptureActive()) {
                        connectionManager.onTransportFailure("视频采集初始化失败");
                        return;
                    }
                    if (audioEnabled.get()) {
                        Logger.i(TAG, "Attempting to setup audio encoder...");
                        setupAudioEncoder();
                    } else {
                        Logger.i(TAG, "Audio is disabled, skipping audio encoder setup");
                    }
                    Logger.i(TAG, "Casting started successfully");
                    if (stateListener != null) stateListener.onSenderStarted();
                } catch (Exception e) {
                    Logger.e(TAG, "Failed to setup encoder", e);
                    notifyError("编码器初始化失败: " + e.getMessage());
                }
            });

        } catch (Exception e) {
            Logger.e(TAG, "Failed to start casting", e);
            notifyError("启动投屏失败: " + e.getMessage());
        }
    }

    /**
     * 停止投屏（非阻塞，不卡UI线程）
     */
    public void stopCasting() {
        if (!running.getAndSet(false)) return;

        userInitiatedStop = true;
        unregisterScreenStateReceiver();
        Logger.i(TAG, "Stopping casting...");

        // 1. 先停止VirtualDisplay（停止向编码器送帧）
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception e) { Logger.e(TAG, "VirtualDisplay release error", e); }
            virtualDisplay = null;
        }

        // 2. 停止处理线程
        if (senderThread != null) {
            senderThread.quitSafely();
            senderThread = null;
        }

        // 3. 将耗时操作移到后台线程（encoder.stop()和mediaProjection.stop()可能阻塞主线程）
        final VideoEncoder enc = videoEncoder;
        final AudioEncoder aEnc = audioEncoder;
        final MediaProjection proj = mediaProjection;
        final MediaProjection.Callback cb = projectionCallback;
        videoEncoder = null;
        audioEncoder = null;
        latestVideoConfig = null;
        mediaProjection = null;
        projectionCallback = null;

        new Thread(() -> {
            try {
                if (enc != null) enc.release();
            } catch (Exception e) {
                Logger.e(TAG, "VideoEncoder release error", e);
            }
            try {
                if (aEnc != null) aEnc.release();
            } catch (Exception e) {
                Logger.e(TAG, "AudioEncoder release error", e);
            }
            try {
                if (proj != null) {
                    if (cb != null) {
                        try {
                            proj.unregisterCallback(cb);
                        } catch (Exception ignored) {}
                    }
                    proj.stop();
                }
            } catch (Exception e) {
                Logger.e(TAG, "MediaProjection stop error", e);
            }
            Logger.i(TAG, "Background cleanup completed");
        }, "CastCleanupThread").start();

        Logger.i(TAG, "Casting stopped. Frames: " + frameCount.get() +
                ", Bytes: " + totalBytesSent.get());

        if (stateListener != null) stateListener.onSenderStopped();
    }

    public boolean isCaptureActive() {
        return running.get() && !capturePaused.get()
                && virtualDisplay != null && videoEncoder != null && videoEncoder.isRunning();
    }

    /** Resume sending through the new socket without consuming the projection token again. */
    public void onTransportReconnected() {
        StreamTransport transport = connectionManager.getStreamTransport();
        if (transport == null || !isCaptureActive()) return;
        byte[] config = latestVideoConfig;
        if (config != null) {
            transport.sendFrame(StreamTransport.TYPE_VIDEO, System.nanoTime(),
                    StreamTransport.FLAG_CONFIG, config, config.length);
        }
        videoEncoder.requestKeyFrame();
        Logger.i(TAG, "Existing capture attached to reconnected transport");
    }

    public void pauseCasting() {
        pauseCaptureForScreenOff();
    }

    public void resumeCasting() {
        resumeCaptureAfterUnlock();
    }

    private void registerScreenStateReceiver() {
        if (screenStateReceiver != null) return;
        screenStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (intent == null || intent.getAction() == null) return;
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    pauseCaptureForScreenOff();
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                    resumeCaptureAfterUnlock();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        context.registerReceiver(screenStateReceiver, filter);
    }

    private void unregisterScreenStateReceiver() {
        if (screenStateReceiver == null) return;
        try {
            context.unregisterReceiver(screenStateReceiver);
        } catch (Exception e) {
            Logger.e(TAG, "unregister screen receiver: " + e.getMessage());
        }
        screenStateReceiver = null;
    }

    /** 锁屏时暂停 VirtualDisplay，不中断 TCP 连接 */
    private void pauseCaptureForScreenOff() {
        if (!running.get() || capturePaused.getAndSet(true)) return;
        Logger.i(TAG, "Pausing screen capture (screen off / projection paused)");
        if (senderHandler != null) {
            senderHandler.post(() -> {
                if (virtualDisplay != null) {
                    try {
                        virtualDisplay.setSurface(null);
                    } catch (Exception e) {
                        Logger.e(TAG, "VirtualDisplay pause failed", e);
                    }
                }
            });
        } else if (virtualDisplay != null) {
            try {
                virtualDisplay.setSurface(null);
            } catch (Exception e) {
                Logger.e(TAG, "VirtualDisplay pause failed", e);
            }
        }
    }

    /** 解锁后重建 VirtualDisplay，恢复投屏画面 */
    private void resumeCaptureAfterUnlock() {
        if (!running.get() || !capturePaused.getAndSet(false)) return;
        Logger.i(TAG, "Resuming screen capture after unlock");
        if (senderHandler == null || mediaProjection == null || videoEncoder == null) {
            capturePaused.set(true);
            return;
        }
        senderHandler.post(() -> {
            if (!running.get() || mediaProjection == null || videoEncoder == null) return;
            if (virtualDisplay == null) {
                Logger.e(TAG, "Cannot resume capture: projection display is gone");
                connectionManager.onTransportFailure("屏幕采集已结束，请重新发起投屏");
                return;
            }
            Surface inputSurface = videoEncoder.getInputSurface();
            if (inputSurface == null) {
                Logger.e(TAG, "Cannot resume capture: encoder surface is null");
                return;
            }
            try {
                virtualDisplay.setSurface(inputSurface);
                videoEncoder.requestKeyFrame();
                Logger.i(TAG, "VirtualDisplay surface restored after unlock");
            } catch (Exception e) {
                Logger.e(TAG, "Failed to restore VirtualDisplay", e);
                capturePaused.set(true);
            }
        });
    }

    private VirtualDisplay createVirtualDisplay(Surface inputSurface) {
        return mediaProjection.createVirtualDisplay(
                "LocalCastPro-Display",
                encodeWidth, encodeHeight,
                context.getResources().getDisplayMetrics().densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                inputSurface, null, null);
    }

    public void requestKeyFrame() {
        if (videoEncoder != null) videoEncoder.requestKeyFrame();
    }

    // ==================== 编码参数 ====================

    private void prepareEncodingParams() {
        codecMime = connectionManager.getNegotiatedCodec() != null &&
                connectionManager.getNegotiatedCodec().equals("hevc")
                ? CodecUtils.MIME_HEVC : CodecUtils.MIME_AVC;

        encodeWidth = connectionManager.getNegotiatedWidth() > 0
                ? connectionManager.getNegotiatedWidth()
                : context.getResources().getDisplayMetrics().widthPixels;

        encodeHeight = connectionManager.getNegotiatedHeight() > 0
                ? connectionManager.getNegotiatedHeight()
                : context.getResources().getDisplayMetrics().heightPixels;

        encodeWidth = CodecUtils.alignToEven(encodeWidth);
        encodeHeight = CodecUtils.alignToEven(encodeHeight);

        encodeBitrate = connectionManager.getNegotiatedBitrate() > 0
                ? connectionManager.getNegotiatedBitrate()
                : CodecUtils.calculateRecommendedBitrate(encodeWidth, encodeHeight, 60);

        encodeFps = connectionManager.getNegotiatedFps() > 0
                ? connectionManager.getNegotiatedFps() : 60;

        // MediaProjection cannot produce frames faster than the source display
        // currently renders. Some vendors expose a 120/144 Hz panel while
        // keeping screen capture at 60 Hz, so capability probing alone is not
        // sufficient here.
        int sourceFps = getCurrentSourceFps();
        if (sourceFps < encodeFps) {
            Logger.w(TAG, "Source display is currently " + sourceFps
                    + "fps; falling back from " + encodeFps + "fps");
            encodeFps = sourceFps;
            connectionManager.setNegotiatedFps(sourceFps);
            encodeBitrate = CodecUtils.calculateRecommendedBitrate(
                    encodeWidth, encodeHeight, sourceFps);
            notifyError("系统屏幕采集当前仅支持 " + sourceFps + " fps，已自动降级");
        }

        int supportedFps = CodecUtils.getBestSupportedFrameRate(
                codecMime, encodeWidth, encodeHeight, encodeFps);
        if (supportedFps > 0 && supportedFps < encodeFps) {
            Logger.w(TAG, "Requested " + encodeFps + "fps is unsupported at "
                    + encodeWidth + "x" + encodeHeight + "; falling back to " + supportedFps + "fps");
            encodeFps = supportedFps;
            connectionManager.setNegotiatedFps(supportedFps);
            encodeBitrate = CodecUtils.calculateRecommendedBitrate(encodeWidth, encodeHeight, supportedFps);
            notifyError("设备不支持所选帧率，已自动降至 " + supportedFps + " fps");
        }

        Logger.i(TAG, "Encoding params: " + codecMime + " " +
                encodeWidth + "x" + encodeHeight + "@" + encodeFps + "fps " +
                (encodeBitrate / 1_000_000) + "Mbps");
    }

    /** Return the selectable preset no higher than the live source refresh rate. */
    private int getCurrentSourceFps() {
        DisplayManager manager = context.getSystemService(DisplayManager.class);
        Display display = manager == null ? null : manager.getDisplay(Display.DEFAULT_DISPLAY);
        float refreshRate = display == null ? 60f : display.getRefreshRate();
        if (refreshRate >= 120f) return 120;
        if (refreshRate >= 60f) return 60;
        return 30;
    }

    // ==================== 视频编码 ====================

    private void setupVideoEncoder() {
        videoEncoder = new VideoEncoder();
        videoEncoder.setOnEncodedDataListener(this::onVideoEncoded);
        videoEncoder.setOnErrorListener(e -> notifyError("视频编码错误: " + e.getMessage()));

        if (!videoEncoder.configure(codecMime, encodeWidth, encodeHeight, encodeBitrate, encodeFps)) {
            notifyError("视频编码器配置失败");
            return;
        }
        videoEncoder.start();

        // 创建VirtualDisplay
        Surface inputSurface = videoEncoder.getInputSurface();
        if (inputSurface == null) {
            notifyError("无法获取编码器Surface");
            return;
        }

        try {
            virtualDisplay = createVirtualDisplay(inputSurface);
            Logger.i(TAG, "VirtualDisplay created: " + encodeWidth + "x" + encodeHeight);
            int captureFps = getVirtualDisplayFps();
            if (captureFps < encodeFps) {
                Logger.w(TAG, "MediaProjection virtual display is limited to " + captureFps
                        + "fps; replacing encoder surface from " + encodeFps + "fps");
                virtualDisplay.setSurface(null);
                videoEncoder.release();
                videoEncoder = null;
                encodeFps = captureFps;
                encodeBitrate = CodecUtils.calculateRecommendedBitrate(
                        encodeWidth, encodeHeight, captureFps);
                connectionManager.setNegotiatedFps(captureFps);
                notifyError("系统屏幕采集实际限制为 " + captureFps + " fps，已自动降级");
                videoEncoder = new VideoEncoder();
                videoEncoder.setOnEncodedDataListener(this::onVideoEncoded);
                videoEncoder.setOnErrorListener(e -> notifyError("视频编码错误: " + e.getMessage()));
                if (!videoEncoder.configure(codecMime, encodeWidth, encodeHeight,
                        encodeBitrate, encodeFps)) {
                    throw new IllegalStateException("降级后编码器配置失败");
                }
                videoEncoder.start();
                Surface replacementSurface = videoEncoder.getInputSurface();
                if (replacementSurface == null) {
                    throw new IllegalStateException("降级后编码器 Surface 为空");
                }
                latestVideoConfig = null;
                virtualDisplay.setSurface(replacementSurface);
            }
        } catch (Exception e) {
            Logger.e(TAG, "VirtualDisplay creation failed", e);
            notifyError("创建虚拟显示器失败: " + e.getMessage());
            if (virtualDisplay != null) {
                try { virtualDisplay.release(); } catch (Exception ignored) {}
                virtualDisplay = null;
            }
        }
    }

    private int getVirtualDisplayFps() {
        Display display = virtualDisplay == null ? null : virtualDisplay.getDisplay();
        float refreshRate = display == null ? 60f : display.getRefreshRate();
        if (refreshRate >= 120f) return 120;
        if (refreshRate >= 60f) return 60;
        return 30;
    }

    /**
     * 视频编码回调 - 通过StreamTransport发送
     */
    private void onVideoEncoded(byte[] data, int offset, int size,
                                long pts, boolean isKeyFrame, boolean isConfig) {
        if (!running.get() || capturePaused.get()) return;

        if (isConfig) latestVideoConfig = Arrays.copyOfRange(data, offset, offset + size);

        StreamTransport transport = connectionManager.getStreamTransport();
        if (transport == null) return;

        // 确定标志
        byte flags = StreamTransport.FLAG_NONE;
        if (isKeyFrame) flags |= StreamTransport.FLAG_KEYFRAME;
        if (isConfig) flags |= StreamTransport.FLAG_CONFIG;

        // 发送帧
        boolean success = transport.sendFrame(StreamTransport.TYPE_VIDEO, pts, flags, data, size);

        if (success) {
            frameCount.incrementAndGet();
            totalBytesSent.addAndGet(size);

            // 定期更新统计
            if (frameCount.get() % 60 == 0) {
                updateStats();
            }
        }
    }

    // ==================== 音频编码 ====================

    private void setupAudioEncoder() {
        Logger.i(TAG, "========== Audio Encoder Setup Start ==========");

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean audioEnabledPref = prefs.getBoolean("audio_enabled", true);
        Logger.i(TAG, "Audio enabled preference: " + audioEnabledPref);

        if (!audioEnabledPref) {
            Logger.w(TAG, "⚠️ Audio disabled by user setting");
            audioEnabled.set(false);
            return;
        }

        // 音频质量 → 码率：low=64k / medium=128k / high=192k
        String quality = prefs.getString("audio_quality", "medium");
        int bitrate;
        switch (quality) {
            case "low":    bitrate = 64000;  break;
            case "high":   bitrate = 192000; break;
            case "medium":
            default:       bitrate = 128000; break;
        }

        // 音频采集模式：
        // - 用户显式选了 microphone/system 则尊重用户选择
        // - 否则按默认：Android 10+ → 系统音频（采系统播放声）；
        //              Android 10 以下 → 麦克风（系统音频 API 不可用）
        String modePref = prefs.getString("audio_mode", null);
        AudioEncoder.AudioMode audioMode;
        if ("microphone".equals(modePref)) {
            audioMode = AudioEncoder.AudioMode.MICROPHONE;
        } else if ("system".equals(modePref)) {
            audioMode = AudioEncoder.AudioMode.SYSTEM_AUDIO;
        } else {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                audioMode = AudioEncoder.AudioMode.SYSTEM_AUDIO;
            } else {
                audioMode = AudioEncoder.AudioMode.MICROPHONE;
            }
        }

        if (!hasRecordAudioPermission()) {
            if (audioMode == AudioEncoder.AudioMode.MICROPHONE) {
                Logger.e(TAG, "❌ RECORD_AUDIO not granted, microphone mode unavailable");
                audioEnabled.set(false);
                notifyAudioUnavailable();
                return;
            }
            Logger.w(TAG, "RECORD_AUDIO not granted, system audio may fail on some devices");
        }

        audioEncoder = new AudioEncoder();
        audioEncoder.setContext(context);
        audioEncoder.setMediaProjection(mediaProjection);
        audioEncoder.setBitrate(bitrate);
        audioEncoder.setOnEncodedAudioListener(this::onAudioEncoded);
        audioEncoder.setOnErrorListener(e -> {
            Logger.e(TAG, "Audio encoder error", e);
        });

        // 尝试用户/默认选定的模式；系统音频在部分 OEM 上静默失败，失败则降级到麦克风
        audioEncoder.setAudioMode(audioMode);
        Logger.i(TAG, "Audio config: mode=" + audioMode + ", quality=" + quality + 
                 ", bitrate=" + (bitrate / 1000) + "kbps");

        if (!audioEncoder.start()) {
            Logger.e(TAG, "❌ Audio encoder start FAILED for mode: " + audioMode);
            
            if (audioMode == AudioEncoder.AudioMode.SYSTEM_AUDIO) {
                Logger.w(TAG, "System audio failed, attempting fallback to MICROPHONE");
                try { audioEncoder.release(); } catch (Exception ignored) {}
                
                audioEncoder = new AudioEncoder();
                audioEncoder.setContext(context);
                audioEncoder.setMediaProjection(mediaProjection);
                audioEncoder.setBitrate(bitrate);
                audioEncoder.setAudioMode(AudioEncoder.AudioMode.MICROPHONE);
                audioEncoder.setOnEncodedAudioListener(this::onAudioEncoded);
                audioEncoder.setOnErrorListener(e -> Logger.e(TAG, "Audio encoder error", e));

                if (!audioEncoder.start()) {
                    Logger.e(TAG, "❌ Microphone audio ALSO failed");
                    Logger.e(TAG, "Audio will be DISABLED for this session");
                    audioEnabled.set(false);
                    notifyAudioUnavailable();
                    return;
                }
                Logger.i(TAG, "✅ Audio encoder started with MICROPHONE fallback");
            } else {
                Logger.e(TAG, "❌ Audio encoder failed, no fallback available");
                audioEnabled.set(false);
                notifyAudioUnavailable();
            }
        } else {
            Logger.i(TAG, "✅ Audio encoder started successfully, mode: " + audioEncoder.getAudioMode());
        }
        Logger.i(TAG, "========== Audio Encoder Setup Complete ==========");
    }

    private boolean fullscreenCapture = false;

    /**
     * 发送端全屏：按电视横屏比例重建 VirtualDisplay，使捕获帧本身适配电视。
     */
    public void setFullscreenCapture(boolean enabled) {
        if (fullscreenCapture == enabled) return;
        fullscreenCapture = enabled;
        if (!running.get() || senderHandler == null) return;
        senderHandler.post(this::recreateCaptureForDisplayMode);
    }

    private void recreateCaptureForDisplayMode() {
        int newW;
        int newH;
        if (fullscreenCapture) {
            DeviceInfo tv = connectionManager.getRemoteDevice();
            if (tv != null && tv.getScreenWidth() > 0 && tv.getScreenHeight() > 0) {
                newW = tv.getScreenWidth();
                newH = tv.getScreenHeight();
            } else {
                newW = 1920;
                newH = 1080;
            }
            if (newH > newW) {
                int tmp = newW;
                newW = newH;
                newH = tmp;
            }
            newW = Math.min(newW, 1920);
            newH = Math.min(newH, 1080);
        } else {
            newW = connectionManager.getNegotiatedWidth();
            newH = connectionManager.getNegotiatedHeight();
        }
        newW = CodecUtils.alignToEven(newW);
        newH = CodecUtils.alignToEven(newH);
        if (newW <= 0 || newH <= 0 || (newW == encodeWidth && newH == encodeHeight)) {
            return;
        }

        Logger.i(TAG, "Recreating capture: " + encodeWidth + "x" + encodeHeight
                + " -> " + newW + "x" + newH + " fullscreen=" + fullscreenCapture);

        if (virtualDisplay == null) {
            notifyError("全屏切换失败：屏幕采集已结束");
            return;
        }
        try {
            virtualDisplay.setSurface(null);
        } catch (Exception e) {
            Logger.e(TAG, "VirtualDisplay surface detach failed", e);
            notifyError("全屏切换失败：无法暂停采集");
            return;
        }
        if (videoEncoder != null) {
            videoEncoder.release();
            videoEncoder = null;
        }

        encodeWidth = newW;
        encodeHeight = newH;
        encodeBitrate = CodecUtils.calculateRecommendedBitrate(newW, newH, encodeFps);

        videoEncoder = new VideoEncoder();
        videoEncoder.setOnEncodedDataListener(this::onVideoEncoded);
        videoEncoder.setOnErrorListener(e -> notifyError("视频编码错误: " + e.getMessage()));
        if (!videoEncoder.configure(codecMime, encodeWidth, encodeHeight, encodeBitrate, encodeFps)) {
            notifyError("全屏切换时编码器配置失败");
            return;
        }
        videoEncoder.start();

        Surface inputSurface = videoEncoder.getInputSurface();
        if (inputSurface == null) {
            notifyError("无法获取编码器Surface");
            return;
        }
        try {
            virtualDisplay.resize(encodeWidth, encodeHeight,
                    context.getResources().getDisplayMetrics().densityDpi);
            virtualDisplay.setSurface(inputSurface);
            latestVideoConfig = null;
            videoEncoder.requestKeyFrame();
            Logger.i(TAG, "VirtualDisplay resized: " + encodeWidth + "x" + encodeHeight);
        } catch (Exception e) {
            Logger.e(TAG, "VirtualDisplay resize failed", e);
            notifyError("全屏切换失败: " + e.getMessage());
        }
    }

    private void notifyAudioUnavailable() {
        if (stateListener != null) {
            new Handler(Looper.getMainLooper()).post(() ->
                stateListener.onError("音频功能不可用，请检查录音权限"));
        }
    }

    private boolean hasRecordAudioPermission() {
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void onAudioEncoded(byte[] data, int offset, int size, long pts) {
        if (!running.get()) return;

        StreamTransport transport = connectionManager.getStreamTransport();
        if (transport == null) {
            // A brief transport gap is expected while the receiver restarts.
            return;
        }

        byte[] audioData = new byte[size];
        System.arraycopy(data, offset, audioData, 0, size);

        boolean success = transport.sendFrame(StreamTransport.TYPE_AUDIO, pts, StreamTransport.FLAG_NONE, audioData, size);
        
        if (!success) {
            Logger.w(TAG, "⚠️ Failed to send audio frame: size=" + size + ", pts=" + pts);
        }
        
        // 每次发送都记录(前10帧),之后每50帧记录一次
        if (frameCount.get() < 10 || frameCount.get() % 50 == 0) {
            Logger.d(TAG, "📤 Audio frame sent #" + frameCount.get() + 
                     ": size=" + size + " bytes, success=" + success);
        }
    }

    // ==================== 统计 ====================

    private void updateStats() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastStatsTime;

        if (elapsed >= 1000) {
            long frames = frameCount.get();
            long bytes = totalBytesSent.get();

            currentFps = Math.min((int) (frames * 1000.0 / elapsed), encodeFps);
            currentBitrate = (int) (bytes * 8.0 / elapsed);

            frameCount.set(0);
            totalBytesSent.set(0);
            lastStatsTime = now;

            if (stateListener != null) {
                stateListener.onStatsUpdated(currentFps, currentBitrate / 1000, 0);
            }

            Logger.d(TAG, "Stats: " + currentFps + " fps, " + (currentBitrate / 1000) + " kbps");
        }
    }

    // ==================== 辅助方法 ====================

    private void notifyError(String error) {
        Logger.e(TAG, error);
        if (stateListener != null) {
            new Handler(Looper.getMainLooper()).post(() -> stateListener.onError(error));
        }
    }

    public boolean isRunning() { return running.get(); }
    public void setAudioEnabled(boolean enabled) { this.audioEnabled.set(enabled); }
    public boolean isAudioEnabled() { return audioEnabled.get(); }
    public int getCurrentFps() { return currentFps; }
    public int getCurrentBitrate() { return currentBitrate; }

    public void setOnSenderStateListener(OnSenderStateListener listener) {
        this.stateListener = listener;
    }
}
