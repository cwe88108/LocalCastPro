package com.localcast.pro.core;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import com.localcast.pro.utils.Logger;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 音频解码器（接收端）
 *
 * 低延迟播放优化：
 * - 严格 FIFO 队列，避免解码器输入重排导致混叠杂音
 * - 队列积压时丢弃最旧帧，保持与视频同步
 * - AudioTrack 使用稳定缓冲区（电视端避免 underrun 爆音）
 */
public class AudioDecoder {

    private static final String TAG = "AudioDecoder";

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private static final int MIN_BUFFER_SIZE = 4096;
    /** 约 240ms @ 48kHz AAC 帧，超出则丢弃旧帧防止音画不同步/叠音 */
    private static final int MAX_QUEUE_FRAMES = 12;
    private static final int ADTS_HEADER_SIZE = 7;
    private static final byte[] AAC_ASC = new byte[]{(byte) 0x11, (byte) 0x88};

    private MediaCodec decoder;
    private AudioTrack audioTrack;
    private HandlerThread audioThread;
    private Handler audioHandler;

    private int outputSampleRate = SAMPLE_RATE;
    private int outputChannelCount = 1;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean released = new AtomicBoolean(false);

    private final LinkedBlockingDeque<AudioInput> inputQueue = new LinkedBlockingDeque<>(MAX_QUEUE_FRAMES);

    private OnErrorListener errorListener;

    public static class AudioInput {
        public final byte[] data;
        public final int offset;
        public final int size;
        public final long presentationTimeUs;

        public AudioInput(byte[] data, int offset, int size, long pts) {
            this.data = data;
            this.offset = offset;
            this.size = size;
            this.presentationTimeUs = pts;
        }
    }

    public interface OnErrorListener {
        void onError(Exception e);
    }

    public boolean start() {
        released.set(false);
        try {
            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE);
            format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(AAC_ASC));

            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            decoder.configure(format, null, null, 0);
            decoder.start();

            int minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
            int bufferSize = Math.max(minBuffer * 2, MIN_BUFFER_SIZE);
            audioTrack = createAudioTrack(bufferSize);

            audioThread = new HandlerThread("AudioDecoderThread",
                    android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            audioThread.start();
            audioHandler = new Handler(audioThread.getLooper());

            running.set(true);
            audioHandler.post(this::decodePlayLoop);

            Logger.i(TAG, "Audio decoder started: " + SAMPLE_RATE + "Hz, buffer=" + bufferSize);
            return true;

        } catch (Exception e) {
            Logger.e(TAG, "Failed to start audio decoder", e);
            if (errorListener != null) errorListener.onError(e);
            releaseResources();
            return false;
        }
    }

    private AudioTrack createAudioTrack(int bufferSize) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            AudioTrack.Builder builder = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM);
            return builder.build();
        }
        return new AudioTrack(
                AudioManager.STREAM_MUSIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize,
                AudioTrack.MODE_STREAM
        );
    }

    public void queueInput(byte[] data, int offset, int size, long pts) {
        if (!running.get() || data == null || size <= 0) return;

        byte[] copy = new byte[size];
        System.arraycopy(data, offset, copy, 0, size);
        AudioInput input = new AudioInput(copy, 0, size, pts);

        while (!inputQueue.offerLast(input)) {
            AudioInput dropped = inputQueue.pollFirst();
            if (dropped == null) break;
            Logger.w(TAG, "Audio queue full, dropping stale frame pts=" + dropped.presentationTimeUs);
        }
    }

    public void stop() {
        running.set(false);
        inputQueue.clear();
        if (audioHandler != null) {
            audioHandler.post(this::releaseResources);
        }
        if (audioThread != null) {
            audioThread.quitSafely();
        }
    }

    public void release() {
        stop();
    }

    private void decodePlayLoop() {
        Logger.i(TAG, "Audio decode/play loop started");
        audioTrack.play();

        while (running.get()) {
            try {
                boolean fed = false;
                while (feedDecoder()) {
                    fed = true;
                }
                drainAndPlay();
                if (!fed && inputQueue.isEmpty()) {
                    Thread.sleep(2);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    Logger.e(TAG, "Audio loop error", e);
                    if (errorListener != null) errorListener.onError(e);
                }
            }
        }

        releaseResources();
        Logger.i(TAG, "Audio decode/play loop stopped");
    }

    private boolean feedDecoder() {
        AudioInput input = inputQueue.peekFirst();
        if (input == null) return false;

        try {
            int inputIndex = decoder.dequeueInputBuffer(5000);
            if (inputIndex < 0) {
                return false;
            }

            ByteBuffer inputBuffer = decoder.getInputBuffer(inputIndex);
            if (inputBuffer == null) return false;

            int offset = input.offset;
            int size = input.size;
            byte[] data = input.data;

            if (size >= ADTS_HEADER_SIZE
                    && (data[offset] & 0xFF) == 0xFF
                    && ((data[offset + 1] & 0xF0) == 0xF0)) {
                offset += ADTS_HEADER_SIZE;
                size -= ADTS_HEADER_SIZE;
            }
            if (size <= 0) {
                inputQueue.pollFirst();
                return true;
            }

            inputBuffer.clear();
            if (size > inputBuffer.remaining()) {
                Logger.w(TAG, "AAC payload too large for input buffer: " + size);
                inputQueue.pollFirst();
                return true;
            }
            inputBuffer.put(data, offset, size);
            decoder.queueInputBuffer(inputIndex, 0, size, input.presentationTimeUs, 0);
            inputQueue.pollFirst();
            return true;
        } catch (Exception e) {
            Logger.e(TAG, "Feed audio decoder error", e);
            inputQueue.pollFirst();
            return false;
        }
    }

    private void drainAndPlay() {
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        while (true) {
            int outputIndex = decoder.dequeueOutputBuffer(bufferInfo, 0);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break;
            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                handleOutputFormatChanged();
                continue;
            }
            if (outputIndex < 0) continue;

            ByteBuffer outputBuffer = decoder.getOutputBuffer(outputIndex);
            if (outputBuffer == null) {
                decoder.releaseOutputBuffer(outputIndex, false);
                continue;
            }

            if (bufferInfo.size > 0) {
                byte[] pcmData = new byte[bufferInfo.size];
                outputBuffer.position(bufferInfo.offset);
                outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
                outputBuffer.get(pcmData, 0, bufferInfo.size);
                writePcmToTrack(pcmData);
            }

            decoder.releaseOutputBuffer(outputIndex, false);
        }
    }

    private void handleOutputFormatChanged() {
        MediaFormat format = decoder.getOutputFormat();
        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            outputSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        }
        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            outputChannelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
        }
        if (outputSampleRate != SAMPLE_RATE || outputChannelCount != 1) {
            Logger.w(TAG, "Unexpected decoder output format: "
                    + outputSampleRate + "Hz, channels=" + outputChannelCount);
        }
    }

    private void writePcmToTrack(byte[] pcmData) {
        if (audioTrack == null || pcmData.length == 0) return;

        int offset = 0;
        while (offset < pcmData.length) {
            int written = audioTrack.write(pcmData, offset, pcmData.length - offset);
            if (written <= 0) {
                if (written == AudioTrack.ERROR_INVALID_OPERATION
                        || written == AudioTrack.ERROR_BAD_VALUE) {
                    Logger.e(TAG, "AudioTrack write error: " + written);
                }
                break;
            }
            offset += written;
        }
    }

    private void releaseResources() {
        if (!released.compareAndSet(false, true)) return;
        try {
            if (audioTrack != null) {
                audioTrack.pause();
                audioTrack.flush();
                audioTrack.stop();
                audioTrack.release();
                audioTrack = null;
            }
            if (decoder != null) {
                decoder.stop();
                decoder.release();
                decoder = null;
            }
        } catch (Exception e) {
            Logger.e(TAG, "Error releasing audio resources", e);
        }
    }

    public void setOnErrorListener(OnErrorListener listener) {
        this.errorListener = listener;
    }

    public boolean isRunning() {
        return running.get();
    }
}
