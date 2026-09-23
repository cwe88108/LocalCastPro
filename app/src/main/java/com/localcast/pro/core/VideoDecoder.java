package com.localcast.pro.core;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.localcast.pro.utils.CodecUtils;
import com.localcast.pro.utils.Logger;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 视频解码器（接收端）
 *
 * 关键特性：
 * 1. Surface直接输出（零拷贝）
 * 2. 配置数据（SPS/PPS/VPS）优先处理
 * 3. 解码后直接渲染到Surface
 * 4. 丢帧策略：缓冲区满时丢弃非关键帧
 */
public class VideoDecoder {

    private static final String TAG = "VideoDecoder";
    private static final int TIMEOUT_US = 5000;
    private static final int INPUT_QUEUE_CAPACITY = 30;

    private MediaCodec decoder;
    private Surface outputSurface;
    private HandlerThread decoderThread;
    private Handler decoderHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean configured = new AtomicBoolean(false);

    private final BlockingQueue<DecodeInput> inputQueue = new LinkedBlockingQueue<>(INPUT_QUEUE_CAPACITY);

    private String mimeType;
    private int videoWidth;
    private int videoHeight;
    private int configWidth;  // 新增:配置的宽度
    private int configHeight; // 新增:配置的高度

    private OnDecoderReadyListener readyListener;
    private OnErrorListener errorListener;

    public static class DecodeInput {
        public byte[] data;
        public int size;
        public long pts;
        public boolean isKeyFrame;
        public boolean isConfig;

        public DecodeInput(byte[] data, int size, long pts, boolean isKeyFrame, boolean isConfig) {
            this.data = data;
            this.size = size;
            this.pts = pts;
            this.isKeyFrame = isKeyFrame;
            this.isConfig = isConfig;
        }
    }

    public interface OnDecoderReadyListener {
        void onDecoderReady(int width, int height);
    }

    public interface OnErrorListener {
        void onError(Exception e);
    }

    /**
     * 配置解码器（带HEVC→AVC降级）
     */
    public boolean configure(String mimeType, Surface surface, int width, int height) {
        return configure(mimeType, surface, width, height, null, 0);
    }

    /**
     * 配置解码器（带HEVC→AVC降级）
     *
     * @param csd      可选的 CSD/参数集字节（SPS/PPS/VPS，Annex B 即 0x00000001 起始码格式）。
     *                 非空时作为 csd-0 直接配置给解码器，使其用码流真实分辨率初始化，
     *                 而非依赖外部传入的 width/height hint。为 null 则仅用 width/height hint。
     * @param csdLength csd 有效字节数
     */
    public boolean configure(String mimeType, Surface surface, int width, int height,
                             byte[] csd, int csdLength) {
        this.mimeType = mimeType;
        this.outputSurface = surface;
        this.configWidth = width;
        this.configHeight = height;

        if (tryConfigure(mimeType, surface, width, height, csd, csdLength)) {
            return true;
        }

        // HEVC失败则降级到AVC
        if (CodecUtils.MIME_HEVC.equals(mimeType)) {
            Logger.w(TAG, "HEVC decoder failed, fallback to AVC");
            this.mimeType = CodecUtils.MIME_AVC;
            return tryConfigure(CodecUtils.MIME_AVC, surface, width, height, csd, csdLength);
        }
        return false;
    }

    private boolean tryConfigure(String mime, Surface surface, int width, int height,
                                 byte[] csd, int csdLength) {
        try {
            decoder = MediaCodec.createDecoderByType(mime);
            if (decoder == null) {
                Logger.e(TAG, "createDecoderByType returned null for: " + mime);
                return false;
            }

            // width/height 仅作为解码器的尺寸 hint（某些解码器需要非 0 值才能启动）。
            // 真实分辨率由 csd-0 参数集决定，并在 INFO_OUTPUT_FORMAT_CHANGED 回调里权威更新。
            MediaFormat format = MediaFormat.createVideoFormat(mime,
                    width > 0 ? width : 1920, height > 0 ? height : 1080);
            format.setInteger(MediaFormat.KEY_WIDTH, width > 0 ? width : 1920);
            format.setInteger(MediaFormat.KEY_HEIGHT, height > 0 ? height : 1080);

            // 关键：若有 CSD，直接作为 csd-0 配置，让解码器按码流真实参数初始化
            if (csd != null && csdLength > 0) {
                ByteBuffer csdBuffer = ByteBuffer.allocate(csdLength);
                csdBuffer.put(csd, 0, csdLength);
                csdBuffer.flip();
                format.setByteBuffer("csd-0", csdBuffer);
                Logger.i(TAG, "Configuring decoder with csd-0 (" + csdLength + " bytes)");
            }

            decoder.configure(format, surface, null, 0);
            decoder.start();

            configured.set(true);
            Logger.i(TAG, "Decoder configured: " + mime + " hint=" + width + "x" + height +
                    (csd != null && csdLength > 0 ? " (with csd-0)" : " (no csd)"));
            return true;
        } catch (Exception e) {
            Logger.e(TAG, "Failed to configure decoder: " + mime + " " + width + "x" + height, e);
            releaseDecoder();
            configured.set(false);
            return false;
        }
    }

    /**
     * 启动解码循环
     */
    public void start() {
        if (!configured.get()) {
            Logger.e(TAG, "Decoder not configured");
            return;
        }

        running.set(true);
        decoderThread = new HandlerThread("VideoDecoderThread",
                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        decoderThread.start();
        decoderHandler = new Handler(decoderThread.getLooper());
        decoderHandler.post(this::decodeLoop);

        // 关键：当 csd-0 在 configure() 阶段已传入时，decoder.start() 后
        // INFO_OUTPUT_FORMAT_CHANGED 可能不再触发，导致 decodeLoop 中拿不到真实分辨率。
        // 这里主动查询一次 outputFormat，确保 readyListener 被调用。
        tryQueryInitialOutputFormat();

        Logger.i(TAG, "Decoder started");
    }

    /**
     * 输入编码数据
     */
    public void queueInput(byte[] data, int size, long pts, boolean isKeyFrame, boolean isConfig) {
        if (!running.get()) return;

        DecodeInput input = new DecodeInput(data, size, pts, isKeyFrame, isConfig);

        if (inputQueue.remainingCapacity() == 0) {
            if (!isKeyFrame && !isConfig) {
                return; // 丢弃非关键帧
            }
            inputQueue.poll(); // 关键帧或配置数据：丢弃最旧的
        }
        inputQueue.offer(input);
    }

    public void stop() {
        running.set(false);
        inputQueue.clear();
        if (decoderThread != null) {
            decoderThread.quitSafely();
            decoderThread = null;
        }
    }

    public void release() {
        stop();
        releaseDecoder();
        configured.set(false);
    }

    public int getVideoWidth() { return videoWidth; }
    public int getVideoHeight() { return videoHeight; }
    public boolean isRunning() { return running.get(); }

    public void setOnDecoderReadyListener(OnDecoderReadyListener l) { this.readyListener = l; }
    public void setOnErrorListener(OnErrorListener l) { this.errorListener = l; }

    // ==================== 初始化查询 ====================

    /**
     * 在 decoder.start() 之后立即查询 outputFormat。
     * 当 csd-0 已在 configure() 中设置时，解码器内部已解析出真实宽高，
     * 但 INFO_OUTPUT_FORMAT_CHANGED 可能不会在 decodeLoop 中触发。
     * 此方法确保 readyListener 在第一时间收到正确的视频尺寸。
     */
    private void tryQueryInitialOutputFormat() {
        try {
            if (decoder != null) {
                MediaFormat format = decoder.getOutputFormat();
                if (format != null && format.containsKey(MediaFormat.KEY_WIDTH)
                        && format.containsKey(MediaFormat.KEY_HEIGHT)) {
                    int w = format.getInteger(MediaFormat.KEY_WIDTH);
                    int h = format.getInteger(MediaFormat.KEY_HEIGHT);
                    if (w > 0 && h > 0 && (w != videoWidth || h != videoHeight)) {
                        videoWidth = w;
                        videoHeight = h;
                        Logger.i(TAG, "Initial outputFormat query: " + w + "x" + h);
                        if (readyListener != null) {
                            readyListener.onDecoderReady(w, h);
                        }
                    }
                }
            }
        } catch (Exception e) {
            // IllegalStateException 等可在过渡态抛出，不阻塞后续 decodeLoop
            Logger.d(TAG, "tryQueryInitialOutputFormat not ready yet (expected)");
        }
    }

    // ==================== 解码循环 ====================

    private void decodeLoop() {
        Logger.i(TAG, "Decode loop started");
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (running.get()) {
            try {
                feedDecoder(bufferInfo);
                drainDecoder(bufferInfo);
            } catch (Exception e) {
                if (running.get()) {
                    String errorMsg = "Decode loop error: " + (e != null ? e.getMessage() : "null");
                    Logger.e(TAG, errorMsg, e);
                    if (errorListener != null) {
                        errorListener.onError(new Exception(errorMsg, e));
                    }
                }
            }
        }

        releaseDecoder();
        Logger.i(TAG, "Decode loop stopped");
    }

    private void feedDecoder(MediaCodec.BufferInfo bufferInfo) {
        DecodeInput input = inputQueue.poll();
        if (input == null) return;

        try {
            if (decoder == null) {
                Logger.e(TAG, "Decoder is null in feedDecoder");
                return;
            }
            
            int inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US);
            if (inputIndex < 0) {
                // 解码器忙,放回队列(如果是关键帧或配置数据)
                if (input.isKeyFrame || input.isConfig) {
                    inputQueue.offer(input);
                }
                return;
            }

            ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
            if (inputBuffer == null) {
                Logger.w(TAG, "Input buffer is null");
                return;
            }

            inputBuffer.clear();
            inputBuffer.put(input.data, 0, input.size);

            int flags = 0;
            if (input.isConfig) {
                flags |= MediaCodec.BUFFER_FLAG_CODEC_CONFIG;
            }

            decoder.queueInputBuffer(inputIndex, 0, input.size, input.pts, flags);

        } catch (Exception e) {
            Logger.e(TAG, "Feed decoder error: " + e.getMessage(), e);
        }
    }

    private void drainDecoder(MediaCodec.BufferInfo bufferInfo) {
        while (true) {
            int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0);

            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format = decoder.getOutputFormat();
                if (format != null) {
                    int newWidth = format.getInteger(MediaFormat.KEY_WIDTH);
                    int newHeight = format.getInteger(MediaFormat.KEY_HEIGHT);
                    Logger.i(TAG, "Video resolution changed: " + videoWidth + "x" + videoHeight + 
                             " → " + newWidth + "x" + newHeight);
                    videoWidth = newWidth;
                    videoHeight = newHeight;
                    if (readyListener != null) {
                        readyListener.onDecoderReady(videoWidth, videoHeight);
                    }
                }
                continue;
            }

            if (outputIndex < 0) continue;

            // 渲染到Surface（零拷贝）
            decoder.releaseOutputBuffer(outputIndex, true);
        }
    }

    private void releaseDecoder() {
        try {
            if (decoder != null) {
                decoder.stop();
                decoder.release();
                decoder = null;
            }
        } catch (Exception e) {
            Logger.e(TAG, "Error releasing decoder", e);
        }
    }
}
