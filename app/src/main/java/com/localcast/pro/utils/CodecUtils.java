package com.localcast.pro.utils;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Size;

import java.util.Locale;

/**
 * 编解码工具类
 * 检测硬件编解码器支持情况，获取最佳编码参数
 */
public class CodecUtils {

    // 编解码器MIME类型
    public static final String MIME_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;
    public static final String MIME_AVC = MediaFormat.MIMETYPE_VIDEO_AVC;
    public static final String MIME_AAC = MediaFormat.MIMETYPE_AUDIO_AAC;

    /**
     * 检测是否支持HEVC硬件编码
     */
    public static boolean isHevcEncoderSupported() {
        return hasHardwareCodec(MIME_HEVC, true);
    }

    /**
     * 检测是否支持HEVC硬件解码
     */
    public static boolean isHevcDecoderSupported() {
        return hasHardwareCodec(MIME_HEVC, false);
    }

    /**
     * 检测是否支持AVC硬件编码
     */
    public static boolean isAvcEncoderSupported() {
        return hasHardwareCodec(MIME_AVC, true);
    }

    /**
     * 检测是否支持AVC硬件解码
     */
    public static boolean isAvcDecoderSupported() {
        return hasHardwareCodec(MIME_AVC, false);
    }

    /**
     * 获取最佳视频编码格式
     * 优先HEVC，降级AVC
     */
    public static String getBestVideoEncoder() {
        if (isHevcEncoderSupported()) {
            return MIME_HEVC;
        }
        return MIME_AVC;
    }

    /**
     * 获取最佳视频解码格式
     */
    public static String getBestVideoDecoder() {
        if (isHevcDecoderSupported()) {
            return MIME_HEVC;
        }
        return MIME_AVC;
    }

    /**
     * 检测硬件编解码器是否存在
     */
    private static boolean hasHardwareCodec(String mimeType, boolean isEncoder) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();

        for (MediaCodecInfo info : codecInfos) {
            if (!isHardwareAccelerated(info)) continue;

            // 检查是否支持指定的MIME类型
            String[] supportedTypes = info.getSupportedTypes();
            for (String type : supportedTypes) {
                if (type.equalsIgnoreCase(mimeType)) {
                    if (isEncoder && info.isEncoder()) return true;
                    if (!isEncoder && !info.isEncoder()) return true;
                }
            }
        }
        return false;
    }

    /**
     * isHardwareAccelerated() 仅 API 29+ 可用；Android 7–9 电视（含大量 TCL 机型）需降级判断。
     */
    private static boolean isHardwareAccelerated(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated();
        }
        String name = info.getName().toLowerCase(Locale.US);
        return !name.startsWith("omx.google.")
                && !name.startsWith("c2.android.")
                && !name.contains(".sw.")
                && !name.contains("software");
    }

    /**
     * 获取编码器支持的最大分辨率
     */
    public static Size getMaxSupportedResolution(String mimeType) {
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();

        int maxWidth = 0;
        int maxHeight = 0;

        for (MediaCodecInfo info : codecInfos) {
            if (!isHardwareAccelerated(info) || !info.isEncoder()) continue;

            String[] supportedTypes = info.getSupportedTypes();
            for (String type : supportedTypes) {
                if (type.equalsIgnoreCase(mimeType)) {
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    MediaCodecInfo.VideoCapabilities videoCaps = caps.getVideoCapabilities();
                    if (videoCaps != null) {
                        int w = videoCaps.getSupportedWidths().getUpper();
                        int h = videoCaps.getSupportedHeights().getUpper();
                        if (w * h > maxWidth * maxHeight) {
                            maxWidth = w;
                            maxHeight = h;
                        }
                    }
                }
            }
        }

        return new Size(maxWidth, maxHeight);
    }

    /** Return the highest supported frame rate from the product's selectable set. */
    public static int getBestSupportedFrameRate(String mimeType, int width, int height, int requestedFps) {
        int[] candidates = requestedFps >= 120 ? new int[]{120, 60, 30}
                : requestedFps >= 60 ? new int[]{60, 30} : new int[]{30};
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        for (int fps : candidates) {
            for (MediaCodecInfo info : codecList.getCodecInfos()) {
                if (!info.isEncoder() || !isHardwareAccelerated(info)) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!type.equalsIgnoreCase(mimeType)) continue;
                    try {
                        MediaCodecInfo.VideoCapabilities caps =
                                info.getCapabilitiesForType(type).getVideoCapabilities();
                        if (caps != null && caps.areSizeAndRateSupported(width, height, fps)) {
                            return fps;
                        }
                    } catch (Exception ignored) {
                        // Try the next codec; vendor capability reporting is not uniform.
                    }
                }
            }
        }
        return 0;
    }

    /**
     * 获取音频AAC编码器支持的采样率
     */
    public static int[] getAacSupportedSampleRates() {
        return new int[]{44100, 48000};
    }

    /**
     * 计算推荐码率（基于分辨率）
     */
    public static int calculateRecommendedBitrate(int width, int height, int fps) {
        int pixels = width * height;
        // 投屏场景优化：使用较低码率避免TCP拥塞导致心跳超时
        double baseBitrate;
        if (pixels <= 640 * 360) {
            baseBitrate = 800_000;    // 360p: 0.8Mbps
        } else if (pixels <= 1280 * 720) {
            baseBitrate = 2_000_000;  // 720p: 2Mbps
        } else if (pixels <= 1920 * 1080) {
            baseBitrate = 4_000_000;  // 1080p: 4Mbps
        } else if (pixels <= 2560 * 1440) {
            baseBitrate = 8_000_000;  // 1440p: 8Mbps
        } else {
            baseBitrate = 12_000_000; // 4K: 12Mbps
        }

        // 根据帧率调整（fpsFactor 对高帧率增加较小）
        double fpsFactor = 1.0 + (fps - 30) / 60.0;
        if (fps <= 30) fpsFactor = 1.0;
        int bitrate = (int) (baseBitrate * fpsFactor);

        // Higher frame rates need a larger but bounded transport budget.
        int maxBitrate = fps >= 120 ? 24_000_000 : (fps >= 60 ? 16_000_000 : 8_000_000);
        return Math.min(bitrate, maxBitrate);
    }

    /**
     * 创建视频编码MediaFormat配置
     * 低延迟优化参数
     */
    public static MediaFormat createVideoEncoderFormat(String mimeType, int width,
                                                       int height, int bitrate, int fps) {
        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);

        // 使用Surface输入模式（零拷贝）
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);

        // 码率设置
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);

        // 帧率
        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);

        // I帧间隔 = 1秒（关键低延迟优化）
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        // 禁用B帧（关键！消除B帧解码延迟）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        }

        // 码率控制模式：可变码率（VBR，广泛支持）
        format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);

        // 编码优先级：实时编码
        format.setInteger(MediaFormat.KEY_PRIORITY, 0); // 0=实时

        return format;
    }

    /**
     * 将尺寸对齐到偶数（编码器要求）
     */
    public static int alignToEven(int size) {
        return (size % 2 == 0) ? size : size - 1;
    }

    /**
     * 创建音频AAC编码MediaFormat
     */
    public static MediaFormat createAudioEncoderFormat(int sampleRate, int bitrate) {
        MediaFormat format = MediaFormat.createAudioFormat(MIME_AAC, sampleRate, 1); // 单声道

        format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2048);

        return format;
    }
}
