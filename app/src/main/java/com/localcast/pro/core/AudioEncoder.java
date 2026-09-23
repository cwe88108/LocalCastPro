package com.localcast.pro.core;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.core.content.ContextCompat;

import com.localcast.pro.utils.CodecUtils;
import com.localcast.pro.utils.Logger;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 音频编码器（发送端）
 * 
 * 采集方式：
 * - Android 10+: AudioRecord + MediaProjection 捕获系统音频
 * - Android 10以下: 仅麦克风（提示用户）
 * 
 * 编码参数：
 * - 格式：AAC-LC
 * - 采样率：48kHz
 * - 比特率：128kbps
 * - 声道：单声道
 * - 采样大小：1024（低延迟）
 */
public class AudioEncoder {

    private static final String TAG = "AudioEncoder";

    // 音频参数
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int DEFAULT_BITRATE = 128000;
    /** AAC-LC 一帧 1024 采样 × 16bit 单声道 = 2048 字节，对齐可减少编码边界杂音 */
    private static final int PCM_FRAME_BYTES = 2048;
    private static final int TIMEOUT_US = 5000;

    // 可配置的码率（由设置页 audio_quality 决定：low=64k / medium=128k / high=192k）
    private int bitrate = DEFAULT_BITRATE;

    // ADTS 头相关（用于给裸 AAC 帧封装，使接收端 MediaCodec AAC 解码器可凭 ADTS 自行解析参数）
    private static final int ADTS_HEADER_SIZE = 7;
    // AAC-LC profile = 1（profile_ObjectType - 1 = 2 - 1）
    private static final int ADTS_PROFILE = 1;
    // sampling_frequency_index：48000Hz = 3（ISO 14496-3 表 1.18）
    private static final int ADTS_SAMPLING_FREQ_INDEX = 3;
    // channel_configuration：mono = 1
    private static final int ADTS_CHANNEL_CONFIG = 1;

    private AudioRecord audioRecord;
    private MediaCodec encoder;
    private HandlerThread audioThread;
    private Handler audioHandler;
    private MediaProjection mediaProjection;
    private Context context;

    // 音频采集模式
    public enum AudioMode {
        MICROPHONE,      // 麦克风模式（可靠，捕获环境声音）
        SYSTEM_AUDIO     // 系统音频模式（实验性，需要Android 10+）
    }
    
    private AudioMode audioMode = AudioMode.MICROPHONE; // 默认使用麦克风模式

    private final AtomicBoolean running = new AtomicBoolean(false);
    private long presentationTimeUs = 0;

    // 诊断计数
    private int readErrorCount = 0;
    private int encodedFrameCount = 0;

    // 回调
    private OnEncodedAudioListener dataListener;
    private OnErrorListener errorListener;

    // ========== 回调接口 ==========

    public interface OnEncodedAudioListener {
        void onEncodedAudio(byte[] data, int offset, int size, long presentationTimeUs);
    }

    public interface OnErrorListener {
        void onError(Exception e);
    }

    // ========== 公共方法 ==========

    /**
     * 设置MediaProjection（用于Android 10+系统音频捕获）
     */
    public void setMediaProjection(MediaProjection projection) {
        this.mediaProjection = projection;
    }

    public void setContext(Context context) {
        this.context = context != null ? context.getApplicationContext() : null;
    }

    /**
     * 设置音频采集模式
     * @param mode 音频模式：MICROPHONE（麦克风）或 SYSTEM_AUDIO（系统音频）
     */
    public void setAudioMode(AudioMode mode) {
        this.audioMode = mode;
        Logger.i(TAG, "Audio mode set to: " + mode);
    }
    
    /**
     * 获取当前音频模式
     */
    public AudioMode getAudioMode() {
        return audioMode;
    }

    /**
     * 设置编码码率（必须在 start() 之前调用）。
     * @param bitrate 比特率（bps），如 64000 / 128000 / 192000
     */
    public void setBitrate(int bitrate) {
        if (bitrate > 0) {
            this.bitrate = bitrate;
        }
    }

    /**
     * 配置并启动音频采集与编码
     */
    public boolean start() {
        try {
            if (context == null || ContextCompat.checkSelfPermission(context,
                    Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Logger.w(TAG, "RECORD_AUDIO permission is not granted");
                return false;
            }
            // 计算缓冲区大小
            int minBufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            int bufferSize = Math.max(minBufferSize, PCM_FRAME_BYTES * 4);

            // 创建AudioRecord
            boolean useSystemAudio = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && mediaProjection != null
                    && audioMode == AudioMode.SYSTEM_AUDIO);

            if (useSystemAudio) {
                // Android 10+ 系统音频捕获
                Logger.i(TAG, "Attempting system audio capture...");
                try {
                    AudioPlaybackCaptureConfiguration config =
                            new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                    .build();

                    audioRecord = new AudioRecord.Builder()
                            .setAudioPlaybackCaptureConfig(config)
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AUDIO_FORMAT)
                                    .setSampleRate(SAMPLE_RATE)
                                    .setChannelMask(CHANNEL_CONFIG)
                                    .build())
                            .setBufferSizeInBytes(bufferSize)
                            .build();

                    if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                        Logger.i(TAG, "System audio capture initialized successfully");
                    } else {
                        Logger.w(TAG, "System audio init failed (state=" + audioRecord.getState() + "), fallback to mic");
                        audioRecord.release();
                        useSystemAudio = false;
                    }
                } catch (Exception e) {
                    Logger.w(TAG, "System audio capture failed: " + e.getMessage() + ", fallback to mic");
                    useSystemAudio = false;
                }
            }

            if (!useSystemAudio) {
                // 降级到麦克风采集
                Logger.i(TAG, "Using microphone audio capture");
                try {
                    audioRecord = new AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            SAMPLE_RATE,
                            CHANNEL_CONFIG,
                            AUDIO_FORMAT,
                            bufferSize
                    );
                } catch (Exception e) {
                    Logger.e(TAG, "Microphone AudioRecord creation failed: " + e.getMessage());
                    return false;
                }
            }

            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                String errorMsg = "AudioRecord initialization failed (state=" + audioRecord.getState() + ")";
                Logger.e(TAG, errorMsg);
                
                // 尝试获取更多信息
                try {
                    int diagMinBufferSize = AudioRecord.getMinBufferSize(
                            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
                    Logger.e(TAG, "Min buffer size: " + diagMinBufferSize + ", requested: " + bufferSize);
                    Logger.e(TAG, "Sample rate: " + SAMPLE_RATE + ", channels: " + CHANNEL_CONFIG + ", format: " + AUDIO_FORMAT);
                } catch (Exception e) {
                    Logger.e(TAG, "Failed to get buffer info: " + e.getMessage());
                }
                
                return false;
            }
            
            Logger.i(TAG, "AudioRecord initialized successfully");

            // 创建AAC编码器
            MediaFormat format = CodecUtils.createAudioEncoderFormat(SAMPLE_RATE, bitrate);
            encoder = MediaCodec.createEncoderByType(CodecUtils.MIME_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            Logger.i(TAG, "MediaCodec encoder started");

            // 创建处理线程
            audioThread = new HandlerThread("AudioEncoderThread",
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            audioThread.start();
            audioHandler = new Handler(audioThread.getLooper());

            running.set(true);
            presentationTimeUs = 0;

            // 启动采集
            try {
                audioRecord.startRecording();
                Logger.i(TAG, "AudioRecord started recording");
            } catch (IllegalStateException e) {
                Logger.e(TAG, "Failed to start recording: " + e.getMessage(), e);
                return false;
            }

            // 启动编码循环
            audioHandler.post(this::encodeLoop);

            Logger.i(TAG, "Audio encoder started: " + SAMPLE_RATE + "Hz, " +
                    bitrate / 1000 + "kbps, mode=" + audioMode);
            return true;

        } catch (Exception e) {
            Logger.e(TAG, "Failed to start audio encoder", e);
            if (errorListener != null) {
                errorListener.onError(e);
            }
            return false;
        }
    }

    /**
     * 停止音频采集与编码
     */
    public void stop() {
        running.set(false);

        if (audioHandler != null) {
            audioHandler.post(this::releaseResources);
            audioThread.quitSafely();
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        stop();
    }

    // ========== 编码循环 ==========

    private void encodeLoop() {
        Logger.i(TAG, "Audio encode loop started");
        byte[] pcmBuffer = new byte[PCM_FRAME_BYTES];

        while (running.get()) {
            try {
                int bytesRead = readPcmFrame(pcmBuffer);
                if (bytesRead <= 0) {
                    readErrorCount++;
                    if (readErrorCount <= 5) {
                        Logger.w(TAG, "audioRecord.read returned: " + bytesRead
                                + " (error #" + readErrorCount + ")");
                    } else if (readErrorCount == 10) {
                        Logger.e(TAG, "audioRecord.read persistent errors, total=" + readErrorCount);
                    }
                    continue;
                }
                if (readErrorCount > 0) {
                    Logger.i(TAG, "audioRecord.read recovered after " + readErrorCount + " errors");
                    readErrorCount = 0;
                }

        if (bytesRead < PCM_FRAME_BYTES) {
                    continue;
                }

                // 获取编码器输入缓冲区
                int inputIndex = encoder.dequeueInputBuffer(TIMEOUT_US);
                if (inputIndex < 0) continue;

                ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
                if (inputBuffer == null) {
                    encoder.queueInputBuffer(inputIndex, 0, 0, 0, 0);
                    continue;
                }

                // 填充PCM数据
                inputBuffer.clear();
                inputBuffer.put(pcmBuffer, 0, bytesRead);

                // 计算时间戳（48kHz, 16bit, mono）
                long durationUs = (long) (bytesRead * 1000000.0 / (SAMPLE_RATE * 2));
                encoder.queueInputBuffer(inputIndex, 0, bytesRead, presentationTimeUs, 0);
                presentationTimeUs += durationUs;

                // 获取编码输出
                drainEncoder();

            } catch (Exception e) {
                if (running.get()) {
                    Logger.e(TAG, "Audio encode loop error", e);
                    if (errorListener != null) {
                        errorListener.onError(e);
                    }
                }
            }
        }

        releaseResources();
        Logger.i(TAG, "Audio encode loop stopped");
    }

    /** 读取完整 AAC 帧对齐的 PCM 块（1024 采样 mono 16bit = 2048 字节） */
    private int readPcmFrame(byte[] buffer) {
        int total = 0;
        while (total < buffer.length && running.get()) {
            int read = audioRecord.read(buffer, total, buffer.length - total);
            if (read <= 0) {
                return total > 0 ? total : read;
            }
            total += read;
        }
        return total;
    }

    /**
     * 取出编码器输出
     *
     * 关键修复：MediaCodec AAC 编码器输出的是裸 AAC 帧（无 ADTS 头、无 csd-0），
     * 接收端 MediaCodec AAC 解码器若只配 KEY_AAC_PROFILE 而无 csd-0/ADTS，在多数设备
     * 会无法解码 → 静音。这里给每一帧封装 7 字节 ADTS 头（自描述 profile/采样率/声道/长度），
     * 解码器可凭 ADTS 自行解析参数，最稳且解码器无关。
     */
    private void drainEncoder() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (true) {
            int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            }
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // AAC 编码器输出的 codec-config（AudioSpecificConfig）不必单独传输，
                // 因为我们用 ADTS 自描述每帧参数，跳过即可。
                continue;
            }
            if (outputIndex < 0) continue;

            ByteBuffer outputBuffer = encoder.getOutputBuffer(outputIndex);
            if (outputBuffer == null) {
                encoder.releaseOutputBuffer(outputIndex, false);
                continue;
            }

            // 跳过 codec-config 输出（BUFFER_FLAG_CODEC_CONFIG），它不是可播放帧
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                encoder.releaseOutputBuffer(outputIndex, false);
                continue;
            }
            if (bufferInfo.size <= 0) {
                encoder.releaseOutputBuffer(outputIndex, false);
                continue;
            }

            // 复制编码数据
            int payloadLen = bufferInfo.size;
            byte[] aacPayload = new byte[payloadLen];
            outputBuffer.position(bufferInfo.offset);
            outputBuffer.get(aacPayload, 0, payloadLen);

            // 加 ADTS 头：7 字节 + payload
            byte[] frame = new byte[ADTS_HEADER_SIZE + payloadLen];
            buildAdtsHeader(frame, ADTS_HEADER_SIZE + payloadLen);
            System.arraycopy(aacPayload, 0, frame, ADTS_HEADER_SIZE, payloadLen);

            // 回调
            if (dataListener != null) {
                dataListener.onEncodedAudio(frame, 0, frame.length,
                        bufferInfo.presentationTimeUs);
                encodedFrameCount++;
                if (encodedFrameCount == 1 || encodedFrameCount % 500 == 0) {
                    Logger.i(TAG, "Encoded audio frame #" + encodedFrameCount
                            + " size=" + frame.length + " pts=" + bufferInfo.presentationTimeUs);
                }
            }

            encoder.releaseOutputBuffer(outputIndex, false);
        }
    }

    /**
     * 构建 7 字节 ADTS 头（无 CRC）。
     *
     * 位域布局（ISO 14496-3）：
     *   byte0: syncwordHigh(8)=0xFF
     *   byte1: syncwordLow(4)=0xF | id(1)=0 | layer(2)=0 | protectionAbsent(1)=1
     *   byte2: profile(2) | samplingFreqIndex(4) | privateBit(1) | channelConfigHigh(1)
     *   byte3: channelConfigLow(2) | originalityCopy(1) | home(1) | copyrightIdBit(1)
     *          | copyrightIdStart(1) | frameLengthHigh(2)
     *   byte4: frameLengthMid(8)
     *   byte5: frameLengthLow(3) | bufferFullnessHigh(5)
     *   byte6: bufferFullnessLow(6) | numberOfRawDataBlocks(2)=0
     *
     * @param header       长度 >= 7 的目标数组
     * @param frameLength  整帧长度（ADTS 头 + AAC payload）
     */
    private void buildAdtsHeader(byte[] header, int frameLength) {
        header[0] = (byte) 0xFF;                                                              // syncword high
        header[1] = (byte) 0xF1;                                                              // syncword low + MPEG-4 + Layer 0 + no CRC
        header[2] = (byte) (((ADTS_PROFILE & 0x3) << 6)
                | ((ADTS_SAMPLING_FREQ_INDEX & 0xF) << 2)
                | ((ADTS_CHANNEL_CONFIG >> 2) & 0x1));                                        // profile + freq + channelHigh
        header[3] = (byte) (((ADTS_CHANNEL_CONFIG & 0x3) << 6)
                | ((frameLength >> 11) & 0x3));                                               // channelLow + frameLength high 2 bits
        header[4] = (byte) ((frameLength >> 3) & 0xFF);                                       // frameLength mid 8 bits
        header[5] = (byte) (((frameLength & 0x7) << 5) | 0x1F);                               // frameLength low 3 bits + bufferFullness(0x7FF>>high)
        header[6] = (byte) 0xFC;                                                              // bufferFullness low + 0 raw data blocks
    }

    /**
     * 释放资源
     */
    private void releaseResources() {
        try {
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
                audioRecord = null;
            }
            if (encoder != null) {
                encoder.stop();
                encoder.release();
                encoder = null;
            }
        } catch (Exception e) {
            Logger.e(TAG, "Error releasing audio resources", e);
        }
    }

    // ========== Setters ==========

    public void setOnEncodedAudioListener(OnEncodedAudioListener listener) {
        this.dataListener = listener;
    }

    public void setOnErrorListener(OnErrorListener listener) {
        this.errorListener = listener;
    }

    public boolean isRunning() {
        return running.get();
    }
}
