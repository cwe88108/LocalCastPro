package com.localcast.pro.core;

import com.localcast.pro.utils.Logger;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP流式传输层 - 参考Scrcpy的socket传输方式
 *
 * 帧格式（14字节头）：
 * ┌──────────┬──────────────┬──────────┬──────────────┬──────────┐
 * │ 帧类型   │   时间戳     │  标志    │  数据长度    │  数据    │
 * │ 1 byte   │   8 bytes    │  1 byte  │  4 bytes     │  N bytes │
 * └──────────┴──────────────┴──────────┴──────────────┴──────────┘
 *
 * 类型: VIDEO=1, AUDIO=2, HEARTBEAT=3, CONTROL=4
 * 标志: KEYFRAME=0x01, CONFIG=0x02
 */
public class StreamTransport {

    private static final String TAG = "StreamTransport";
    public static final int FRAME_HEADER_SIZE = 14;

    // 帧类型
    public static final byte TYPE_VIDEO = 1;
    public static final byte TYPE_AUDIO = 2;
    public static final byte TYPE_HEARTBEAT = 3;
    public static final byte TYPE_CONTROL = 4;

    // 帧标志
    public static final byte FLAG_NONE = 0;
    public static final byte FLAG_KEYFRAME = 0x01;
    public static final byte FLAG_CONFIG = 0x02;

    private Socket socket;
    private DataInputStream inputStream;
    private DataOutputStream outputStream;
    private final Object writeLock = new Object();
    private final AtomicBoolean running = new AtomicBoolean(false);

    // 接收线程
    private Thread readThread;

    // 回调
    private OnFrameReceivedListener frameListener;
    private OnErrorListener errorListener;

    public interface OnFrameReceivedListener {
        void onFrameReceived(byte type, long timestamp, byte flags, byte[] data, int length);
    }

    public interface OnErrorListener {
        void onError(String error);
    }

    /**
     * 从已建立的Socket创建StreamTransport
     */
    public void attach(Socket socket) throws IOException {
        this.socket = socket;
        this.inputStream = new DataInputStream(socket.getInputStream());
        this.outputStream = new DataOutputStream(socket.getOutputStream());
        this.running.set(true);
        Logger.i(TAG, "StreamTransport attached to " + socket.getRemoteSocketAddress());
    }

    /**
     * 启动接收线程
     */
    public void startReading() {
        if (!running.get() || inputStream == null) {
            Logger.e(TAG, "Cannot start reading: not attached");
            return;
        }
        if (readThread != null && readThread.isAlive()) {
            return;
        }
        readThread = new Thread(this::readLoop, "StreamReadThread");
        readThread.setPriority(Thread.MAX_PRIORITY);
        readThread.start();
    }

    /**
     * 发送一个帧（线程安全）
     */
    public boolean sendFrame(byte type, long timestamp, byte flags, byte[] data, int length) {
        if (!running.get() || outputStream == null) return false;

        synchronized (writeLock) {
            try {
                outputStream.writeByte(type);
                outputStream.writeLong(timestamp);
                outputStream.writeByte(flags);
                outputStream.writeInt(length);
                if (length > 0 && data != null) {
                    outputStream.write(data, 0, length);
                }
                outputStream.flush();
                return true;
            } catch (IOException e) {
                if (running.get()) {
                    Logger.e(TAG, "Send frame error", e);
                    running.set(false);
                    if (errorListener != null) {
                        errorListener.onError("发送失败: " + e.getMessage());
                    }
                }
                return false;
            }
        }
    }

    /**
     * 安全发送心跳（失败不影响传输层状态）
     */
    public boolean sendHeartbeatSafe() {
        if (!running.get() || outputStream == null) return false;

        synchronized (writeLock) {
            try {
                outputStream.writeByte(TYPE_HEARTBEAT);
                outputStream.writeLong(System.nanoTime());
                outputStream.writeByte(FLAG_NONE);
                outputStream.writeInt(0);
                outputStream.flush();
                return true;
            } catch (IOException e) {
                // 心跳发送失败不关闭传输层，由读取线程检测连接断开
                Logger.w(TAG, "Heartbeat send failed: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * 发送心跳
     */
    public boolean sendHeartbeat() {
        return sendFrame(TYPE_HEARTBEAT, System.nanoTime(), FLAG_NONE, null, 0);
    }

    /**
     * 接收循环：从TCP流中读取帧
     */
    private void readLoop() {
        Logger.i(TAG, "Read thread started");
        byte[] headerBuf = new byte[FRAME_HEADER_SIZE];

        while (running.get()) {
            try {
                // 读取帧头（14字节）
                readFully(inputStream, headerBuf, 0, FRAME_HEADER_SIZE);

                ByteBuffer header = ByteBuffer.wrap(headerBuf);
                byte type = header.get();
                long timestamp = header.getLong();
                byte flags = header.get();
                int length = header.getInt();

                // 合理性检查
                if (length < 0 || length > 10 * 1024 * 1024) {
                    Logger.e(TAG, "Invalid frame length: " + length + ", closing");
                    break;
                }

                if (type == TYPE_HEARTBEAT) {
                    if (length != 0) {
                        Logger.e(TAG, "Invalid heartbeat length: " + length);
                        break;
                    }
                    // 心跳不需要数据
                    if (frameListener != null) {
                        frameListener.onFrameReceived(type, timestamp, flags, null, 0);
                    }
                    continue;
                }

                // 读取帧数据
                byte[] data = new byte[length];
                if (length > 0) {
                    readFully(inputStream, data, 0, length);
                }

                // 回调
                if (frameListener != null) {
                    frameListener.onFrameReceived(type, timestamp, flags, data, length);
                }

            } catch (IOException e) {
                if (running.get()) {
                    Logger.e(TAG, "Read error", e);
                    running.set(false);
                    if (errorListener != null) {
                        errorListener.onError("接收失败: " + e.getMessage());
                    }
                }
                break;
            }
        }

        Logger.i(TAG, "Read thread stopped");
    }

    /**
     * 精确读取指定字节数（阻塞直到读完）
     */
    private void readFully(DataInputStream in, byte[] buffer, int offset, int length) throws IOException {
        int remaining = length;
        while (remaining > 0) {
            int read = in.read(buffer, offset, remaining);
            if (read < 0) {
                throw new IOException("Stream closed");
            }
            offset += read;
            remaining -= read;
        }
    }

    /**
     * 关闭传输
     */
    public void close() {
        running.set(false);

        if (readThread != null) {
            readThread.interrupt();
        }

        try {
            if (inputStream != null) inputStream.close();
        } catch (IOException ignored) {}

        try {
            if (outputStream != null) outputStream.close();
        } catch (IOException ignored) {}

        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {}

        inputStream = null;
        outputStream = null;
        socket = null;

        Logger.i(TAG, "StreamTransport closed");
    }

    public boolean isRunning() {
        return running.get();
    }

    public void setOnFrameReceivedListener(OnFrameReceivedListener listener) {
        this.frameListener = listener;
    }

    public void setOnErrorListener(OnErrorListener listener) {
        this.errorListener = listener;
    }
}
