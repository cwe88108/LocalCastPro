package com.localcast.pro.core;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.localcast.pro.utils.CodecUtils;
import com.localcast.pro.utils.Logger;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 视频编码器（发送端）
 *
 * 关键特性：
 * 1. Surface输入模式（零拷贝）
 * 2. 强制硬件编码（HEVC > AVC）
 * 3. NAL单元格式转换为Annex B（起始码0x00000001）
 * 4. 配置数据（SPS/PPS/VPS）单独输出
 */
public class VideoEncoder {

    private static final String TAG = "VideoEncoder";
    private static final int TIMEOUT_US = 10000;

    private MediaCodec encoder;
    private Surface inputSurface;
    private HandlerThread encoderThread;
    private Handler encoderHandler;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean configured = new AtomicBoolean(false);

    private String mimeType;
    private int width;
    private int height;
    private int bitrate;
    private int fps;

    private OnEncodedDataListener dataListener;
    private OnErrorListener errorListener;

    public interface OnEncodedDataListener {
        void onEncodedData(byte[] data, int offset, int size, long pts,
                           boolean isKeyFrame, boolean isConfig);
    }

    public interface OnErrorListener {
        void onError(Exception e);
    }

    /**
     * 配置编码器
     */
    public boolean configure(String mimeType, int width, int height, int bitrate, int fps) {
        this.mimeType = mimeType;
        this.width = width;
        this.height = height;
        this.bitrate = bitrate;
        this.fps = fps;

        if (tryConfigure(mimeType, width, height, bitrate, fps)) {
            return true;
        }

        // HEVC失败则降级AVC
        if (CodecUtils.MIME_HEVC.equals(mimeType)) {
            Logger.w(TAG, "HEVC failed, fallback to AVC");
            this.mimeType = CodecUtils.MIME_AVC;
            return tryConfigure(CodecUtils.MIME_AVC, width, height, bitrate, fps);
        }
        return false;
    }

    private boolean tryConfigure(String mime, int w, int h, int br, int fps) {
        try {
            encoder = MediaCodec.createEncoderByType(mime);
            MediaFormat format = CodecUtils.createVideoEncoderFormat(mime, w, h, br, fps);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = encoder.createInputSurface();
            encoder.start();

            // 校验编码器实际输出分辨率
            try {
                MediaFormat actualFormat = encoder.getOutputFormat();
                if (actualFormat.containsKey(MediaFormat.KEY_WIDTH) && 
                    actualFormat.containsKey(MediaFormat.KEY_HEIGHT)) {
                    int actualWidth = actualFormat.getInteger(MediaFormat.KEY_WIDTH);
                    int actualHeight = actualFormat.getInteger(MediaFormat.KEY_HEIGHT);
                    if (actualWidth != width || actualHeight != height) {
                        Logger.w(TAG, "Encoder adjusted resolution: requested " + width + "x" + height + 
                                 " → actual " + actualWidth + "x" + actualHeight);
                    } else {
                        Logger.i(TAG, "Encoder output matches requested: " + width + "x" + height);
                    }
                }
            } catch (Exception e) {
                Logger.d(TAG, "Cannot query encoder output format yet (expected during init)");
            }

            configured.set(true);
            Logger.i(TAG, "Encoder configured: " + mime + " " + w + "x" + h + "@" + fps + "fps " + (br/1_000_000) + "Mbps");
            return true;
        } catch (Exception e) {
            Logger.e(TAG, "Failed to configure encoder: " + mime, e);
            releaseEncoder();
            configured.set(false);
            return false;
        }
    }

    /**
     * 启动编码循环
     */
    public void start() {
        if (!configured.get()) {
            Logger.e(TAG, "Encoder not configured");
            return;
        }

        running.set(true);
        encoderThread = new HandlerThread("VideoEncoderThread",
                android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
        encoderThread.start();
        encoderHandler = new Handler(encoderThread.getLooper());
        encoderHandler.post(this::encodeLoop);

        Logger.i(TAG, "Encoder started");
    }

    /**
     * 停止
     */
    public void stop() {
        running.set(false);
        if (encoderThread != null) {
            encoderThread.quitSafely();
            encoderThread = null;
        }
    }

    /**
     * 释放
     */
    public void release() {
        stop();
        releaseEncoder();
        configured.set(false);
    }

    public Surface getInputSurface() { return inputSurface; }
    public boolean isRunning() { return running.get(); }

    public void adjustBitrate(int newBitrate) {
        if (encoder != null && running.get()) {
            this.bitrate = newBitrate;
            android.os.Bundle params = new android.os.Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, newBitrate);
            encoder.setParameters(params);
        }
    }

    public void requestKeyFrame() {
        if (encoder != null && running.get()) {
            android.os.Bundle params = new android.os.Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            encoder.setParameters(params);
        }
    }

    public void setOnEncodedDataListener(OnEncodedDataListener l) { this.dataListener = l; }
    public void setOnErrorListener(OnErrorListener l) { this.errorListener = l; }

    // ==================== 编码循环 ====================

    private void encodeLoop() {
        Logger.i(TAG, "Encode loop started");
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        int consecutiveErrors = 0;

        while (running.get()) {
            try {
                int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US);

                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Logger.i(TAG, "Output format: " + encoder.getOutputFormat());
                    consecutiveErrors = 0;
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    consecutiveErrors = 0;
                    continue;
                }
                if (outputIndex < 0) continue;

                ByteBuffer outputBuffer = encoder.getOutputBuffer(outputIndex);
                if (outputBuffer == null) {
                    encoder.releaseOutputBuffer(outputIndex, false);
                    continue;
                }

                boolean isKeyFrame = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_SYNC_FRAME) != 0;
                boolean isConfig = (bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;

                // 复制数据
                byte[] rawData = new byte[bufferInfo.size];
                outputBuffer.position(bufferInfo.offset);
                outputBuffer.get(rawData, 0, bufferInfo.size);

                // 转换为Annex B格式（添加起始码）
                byte[] annexBData = convertToAnnexB(rawData, 0, bufferInfo.size);

                // 回调
                if (dataListener != null && annexBData.length > 0) {
                    dataListener.onEncodedData(annexBData, 0, annexBData.length,
                            bufferInfo.presentationTimeUs, isKeyFrame, isConfig);
                }

                encoder.releaseOutputBuffer(outputIndex, false);
                consecutiveErrors = 0;

            } catch (Exception e) {
                consecutiveErrors++;
                if (running.get()) {
                    Logger.e(TAG, "Encode loop error (" + consecutiveErrors + ")", e);
                    if (consecutiveErrors >= 10) {
                        Logger.e(TAG, "Too many consecutive errors, stopping encoder");
                        if (errorListener != null) errorListener.onError(e);
                        break;
                    }
                }
            }
        }

        releaseEncoder();
        Logger.i(TAG, "Encode loop stopped");
    }

    /**
     * 将长度前缀NAL单元转换为Annex B格式（0x00000001起始码）
     *
     * MediaCodec输出格式：[4字节长度][NAL数据][4字节长度][NAL数据]...
     * Annex B格式：      [0x00000001][NAL数据][0x00000001][NAL数据]...
     */
    private byte[] convertToAnnexB(byte[] data, int offset, int length) {
        if (data == null || length < 4) return data;

        // 检查是否已经是Annex B格式
        if (hasAnnexBStartCode(data, offset)) {
            byte[] result = new byte[length];
            System.arraycopy(data, offset, result, 0, length);
            return result;
        }

        // 转换长度前缀为起始码
        ByteBuffer buf = ByteBuffer.wrap(data, offset, length);
        ByteBuffer output = ByteBuffer.allocate(length + 64); // 预留空间

        try {
            while (buf.remaining() >= 4) {
                int nalLength = buf.getInt();

                if (nalLength <= 0 || nalLength > buf.remaining()) {
                    // 无效长度，可能数据已经是Annex B或损坏
                    break;
                }

                // 写入起始码
                output.put((byte) 0);
                output.put((byte) 0);
                output.put((byte) 0);
                output.put((byte) 1);

                // 写入NAL单元
                byte[] nalUnit = new byte[nalLength];
                buf.get(nalUnit);
                output.put(nalUnit);
            }
        } catch (Exception e) {
            Logger.w(TAG, "NAL conversion partial: " + e.getMessage());
        }

        int outputLength = output.position();
        if (outputLength == 0) {
            // 转换失败，返回原始数据
            byte[] result = new byte[length];
            System.arraycopy(data, offset, result, 0, length);
            return result;
        }

        byte[] result = new byte[outputLength];
        output.flip();
        output.get(result);
        return result;
    }

    /**
     * 检查是否有Annex B起始码
     */
    private boolean hasAnnexBStartCode(byte[] data, int offset) {
        if (data.length - offset < 4) return false;
        return data[offset] == 0 && data[offset + 1] == 0 &&
               data[offset + 2] == 0 && data[offset + 3] == 1;
    }

    private void releaseEncoder() {
        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
                encoder = null;
            }
        } catch (Exception e) {
            Logger.e(TAG, "Error releasing encoder", e);
        }
        try {
            if (inputSurface != null) {
                inputSurface.release();
                inputSurface = null;
            }
        } catch (Exception e) {
            Logger.e(TAG, "Error releasing surface", e);
        }
    }
}
