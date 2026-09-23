package com.localcast.pro.core;

import com.localcast.pro.utils.Logger;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 连接管理器 - TCP连接管理 + 流式传输
 *
 * 架构（参考Scrcpy）：
 * - 接收端启动TCP Server，监听端口
 * - 发送端作为TCP Client连接
 * - TCP握手：JSON交换设备信息和编码参数
 * - 数据传输：同一个Socket上发送二进制帧
 */
public class ConnectionManager {

    private static final String TAG = "ConnectionManager";
    public static final int TCP_PORT = 8889;

    private static final long HEARTBEAT_INTERVAL_MS = 2000;
    private static final long HEARTBEAT_TIMEOUT_MS = 20000;
    private static final int MAX_HANDSHAKE_CHARS = 16 * 1024;

    public enum ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private DeviceInfo localDevice;
    private DeviceInfo remoteDevice;

    private ServerSocket serverSocket;
    private Socket dataSocket;
    private Thread acceptThread;
    private Thread connectThread;

    private StreamTransport streamTransport;

    private ScheduledExecutorService heartbeatExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong lastHeartbeatReceived = new AtomicLong(0);

    private String negotiatedCodec = "avc";
    private int negotiatedWidth = 1920;
    private int negotiatedHeight = 1080;
    private int negotiatedBitrate = 4_000_000;
    private int negotiatedFps = 60;
    private int requestedFps = 30;

    private final AtomicBoolean disconnecting = new AtomicBoolean(false);
    /** 客户端已连接或正在连接 */
    private final AtomicBoolean clientActive = new AtomicBoolean(false);
    /** TCP Server 正在监听 */
    private final AtomicBoolean serverRunning = new AtomicBoolean(false);
    /** 握手完成但尚未通知 listener（接收端 Surface 未就绪） */
    private volatile boolean pendingConnectedNotification = false;

    private OnConnectionStateListener stateListener;

    public interface OnConnectionStateListener {
        void onDeviceConnected(DeviceInfo remoteDevice);
        void onDeviceDisconnected(String reason);
    }

    public void init(DeviceInfo localDevice) {
        this.localDevice = localDevice;
        Logger.i(TAG, "ConnectionManager initialized, device: " + localDevice.getDeviceName());
    }

    // ==================== 服务端（接收端） ====================

    public void startAsServer() {
        if (serverRunning.get()) {
            Logger.w(TAG, "Server already running");
            return;
        }
        serverRunning.set(true);
        running.set(true);

        acceptThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress(TCP_PORT));
                Logger.i(TAG, "TCP server listening on port " + TCP_PORT);

                while (serverRunning.get()) {
                    try {
                        Socket client = serverSocket.accept();
                        Logger.i(TAG, "Client connected from " + client.getRemoteSocketAddress());
                        handleIncomingClient(client);
                    } catch (IOException e) {
                        if (serverRunning.get()) {
                            Logger.e(TAG, "Accept error", e);
                        }
                    }
                }
            } catch (IOException e) {
                Logger.e(TAG, "Server socket error", e);
                serverRunning.set(false);
                if (!clientActive.get()) {
                    running.set(false);
                }
            }
        }, "TcpAcceptThread");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void handleIncomingClient(Socket client) {
        try {
            // 新连接到来时先清理旧会话，避免首次失败后无法重连
            if (streamTransport != null || dataSocket != null) {
                Logger.i(TAG, "Closing previous client session for new connection");
                resetClientSession();
            }

            client.setTcpNoDelay(true);
            client.setSoTimeout(10000);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter writer = new PrintWriter(client.getOutputStream(), true);

            String requestJson = readLimitedLine(reader);
            if (requestJson == null) { client.close(); return; }

            JSONObject request = new JSONObject(requestJson);
            if (!"CONNECT_REQUEST".equals(request.optString("command", ""))) {
                client.close(); return;
            }

            DeviceInfo senderInfo = DeviceInfo.fromJson(request.getJSONObject("deviceInfo"));
            this.remoteDevice = senderInfo;

            negotiateCodecParams(request);

            JSONObject response = new JSONObject();
            response.put("command", "CONNECT_RESPONSE");
            response.put("status", "OK");
            response.put("deviceInfo", localDevice.toJson());
            response.put("codec", negotiatedCodec);
            response.put("width", negotiatedWidth);
            response.put("height", negotiatedHeight);
            response.put("bitrate", negotiatedBitrate);
            response.put("fps", negotiatedFps);
            writer.println(response.toString());

            client.setSoTimeout(0);

            this.dataSocket = client;
            this.streamTransport = new StreamTransport();
            this.streamTransport.attach(client);
            disconnecting.set(false);

            Logger.i(TAG, "Handshake OK (server). Codec=" + negotiatedCodec +
                    " " + negotiatedWidth + "x" + negotiatedHeight);

            setState(ConnectionState.CONNECTED);
            notifyDeviceConnected();
        } catch (Exception e) {
            Logger.e(TAG, "Server handshake failed", e);
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    private void notifyDeviceConnected() {
        if (stateListener != null) {
            pendingConnectedNotification = false;
            stateListener.onDeviceConnected(remoteDevice);
        } else {
            pendingConnectedNotification = true;
            Logger.i(TAG, "Connection ready, waiting for listener registration");
        }
    }

    // ==================== 客户端（发送端） ====================

    public void connectTo(String host) {
        if (clientActive.get()) {
            Logger.w(TAG, "Client connection already active");
            return;
        }
        clientActive.set(true);
        // A newly requested connection is a new failure-reporting session.
        // Keep the guard set after a transport error so the read and write
        // sides of the same broken socket cannot tear the cast down twice.
        disconnecting.set(false);
        running.set(true);
        setState(ConnectionState.CONNECTING);

        connectThread = new Thread(() -> {
            Exception lastError = null;
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    if (attempt > 1) {
                        Logger.i(TAG, "Retry connect attempt " + attempt + "/3 to " + host);
                        Thread.sleep(800L * (attempt - 1));
                    }
                    if (connectOnce(host)) {
                        return;
                    }
                } catch (Exception e) {
                    lastError = e;
                    Logger.w(TAG, "Connect attempt " + attempt + " failed: " + e.getMessage());
                    clearSession(false);
                }
            }
            Logger.e(TAG, "Connection failed after retries", lastError);
            clientActive.set(false);
            if (!serverRunning.get()) {
                running.set(false);
            }
            setState(ConnectionState.DISCONNECTED);
            if (stateListener != null) {
                String msg = lastError != null ? lastError.getMessage() : "未知错误";
                stateListener.onDeviceDisconnected("连接失败: " + msg);
            }
        }, "TcpConnectThread");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private boolean connectOnce(String host) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, TCP_PORT), 5000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10000);

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter writer = new PrintWriter(socket.getOutputStream(), true);

        JSONObject request = new JSONObject();
        request.put("command", "CONNECT_REQUEST");
        request.put("deviceInfo", localDevice.toJson());
        request.put("supportedCodecs", localDevice.isHevcSupported() ? "hevc,avc" : "avc");
        request.put("screenWidth", localDevice.getScreenWidth());
        request.put("screenHeight", localDevice.getScreenHeight());
        request.put("requestedFps", requestedFps);
        writer.println(request.toString());

        String responseJson = readLimitedLine(reader);
        if (responseJson == null) throw new IOException("No response");

        JSONObject response = new JSONObject(responseJson);
        if (!"OK".equals(response.optString("status", ""))) {
            throw new IOException("Rejected: " + response.optString("status"));
        }

        this.remoteDevice = DeviceInfo.fromJson(response.getJSONObject("deviceInfo"));
        this.negotiatedCodec = response.optString("codec", "avc");
        this.negotiatedWidth = response.optInt("width", localDevice.getScreenWidth());
        this.negotiatedHeight = response.optInt("height", localDevice.getScreenHeight());
        this.negotiatedBitrate = response.optInt("bitrate", 4000000);
        this.negotiatedFps = response.optInt("fps", 60);

        socket.setSoTimeout(0);

        this.dataSocket = socket;
        this.streamTransport = new StreamTransport();
        this.streamTransport.attach(socket);
        disconnecting.set(false);

        Logger.i(TAG, "Handshake OK (client). Codec=" + negotiatedCodec +
                " " + negotiatedWidth + "x" + negotiatedHeight);

        setState(ConnectionState.CONNECTED);
        notifyDeviceConnected();
        return true;
    }

    /** Reject oversized or unterminated peer handshakes before parsing JSON. */
    static String readLimitedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int next;
        while ((next = reader.read()) != -1) {
            if (next == '\n') return line.toString();
            if (line.length() >= MAX_HANDSHAKE_CHARS) {
                throw new IOException("Handshake too large");
            }
            if (next != '\r') line.append((char) next);
        }
        if (line.length() == 0) return null;
        throw new IOException("Unterminated handshake");
    }

    /** 仅清理客户端会话，接收端 TCP Server 继续监听以便重连 */
    public synchronized void resetClientSession() {
        clearSession(true);
    }

    private synchronized void clearSession(boolean clearClientActive) {
        if (heartbeatExecutor != null) {
            heartbeatExecutor.shutdownNow();
            heartbeatExecutor = null;
        }
        if (streamTransport != null) {
            streamTransport.close();
            streamTransport = null;
        }
        if (dataSocket != null) {
            try {
                dataSocket.close();
            } catch (IOException ignored) {}
            dataSocket = null;
        }
        remoteDevice = null;
        pendingConnectedNotification = false;
        if (clearClientActive) {
            clientActive.set(false);
        }
        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
            setState(ConnectionState.DISCONNECTED);
        }
    }

    /**
     * Handle a broken stream without tearing down a receiver's listening
     * socket. This is the common path for Wi-Fi drops and sender restarts.
     */
    public void onTransportFailure(String reason) {
        if (!disconnecting.compareAndSet(false, true)) {
            Logger.i(TAG, "Ignoring duplicate transport failure: " + reason);
            return;
        }
        Logger.w(TAG, "Transport failure: " + reason);
        boolean keepServerListening = serverRunning.get();
        resetClientSession();
        running.set(keepServerListening);
        setState(ConnectionState.DISCONNECTED);
        if (stateListener != null) {
            stateListener.onDeviceDisconnected(reason);
        }
    }

    // ==================== 协商 ====================

    private void negotiateCodecParams(JSONObject request) {
        // AVC is the stable interoperability baseline. A runtime HEVC fallback
        // previously changed only the sender, leaving the receiver decoding HEVC
        // and producing a connected-but-black-screen session.
        negotiatedCodec = "avc";

        int senderW = request.optInt("screenWidth", 1920);
        int senderH = request.optInt("screenHeight", 1080);
        negotiatedWidth = Math.min(senderW, localDevice.getScreenWidth());
        negotiatedHeight = Math.min(senderH, localDevice.getScreenHeight());
        negotiatedWidth = (negotiatedWidth % 2 == 0) ? negotiatedWidth : negotiatedWidth - 1;
        negotiatedHeight = (negotiatedHeight % 2 == 0) ? negotiatedHeight : negotiatedHeight - 1;

        negotiatedFps = sanitizeFps(request.optInt("requestedFps", 30));
        negotiatedBitrate = com.localcast.pro.utils.CodecUtils
                .calculateRecommendedBitrate(negotiatedWidth, negotiatedHeight, negotiatedFps);

        Logger.i(TAG, "Negotiated: " + negotiatedCodec + " " +
                negotiatedWidth + "x" + negotiatedHeight + "@" + negotiatedFps + "fps");
    }

    // ==================== 心跳 ====================

    public void startHeartbeat() {
        lastHeartbeatReceived.set(System.currentTimeMillis());
        if (heartbeatExecutor != null) heartbeatExecutor.shutdownNow();

        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "HeartbeatThread");
            t.setDaemon(true);
            return t;
        });

        heartbeatExecutor.scheduleWithFixedDelay(() -> {
            if (!running.get() || streamTransport == null) return;

            // 先检查超时（在发送之前，避免send阻塞导致误判）
            long elapsed = System.currentTimeMillis() - lastHeartbeatReceived.get();
            if (elapsed > HEARTBEAT_TIMEOUT_MS) {
                Logger.w(TAG, "Heartbeat timeout: " + elapsed + "ms");
                onTransportFailure("心跳超时");
                return;
            }

            // 发送心跳（使用safe方法，失败不关闭传输层）
            streamTransport.sendHeartbeatSafe();
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public void updateHeartbeat() {
        lastHeartbeatReceived.set(System.currentTimeMillis());
    }

    // ==================== 断开 ====================

    public void disconnectSilently() {
        if (!disconnecting.compareAndSet(false, true)) return;
        try { doDisconnect(); } finally { disconnecting.set(false); }
    }

    public void disconnect(String reason) {
        if (!disconnecting.compareAndSet(false, true)) return;
        try {
            doDisconnect();
            if (stateListener != null) stateListener.onDeviceDisconnected(reason);
        } finally { disconnecting.set(false); }
    }

    private void doDisconnect() {
        // A full stop ends the sender session. Leaving clientActive set here
        // causes every later manual connection to be silently rejected.
        clearSession(true);
        serverRunning.set(false);
        running.set(false);
        pendingConnectedNotification = false;
        setState(ConnectionState.DISCONNECTED);

        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        try {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        } catch (IOException ignored) {}
        serverSocket = null;
        Logger.i(TAG, "Disconnected (server stopped)");
    }

    public void release() {
        disconnectSilently();
        remoteDevice = null;
    }

    // ==================== 状态 ====================

    private void setState(ConnectionState s) {
        if (state != s) { state = s; Logger.i(TAG, "State: " + s); }
    }

    // ==================== Getters ====================

    public ConnectionState getState() { return state; }
    public DeviceInfo getRemoteDevice() { return remoteDevice; }
    public StreamTransport getStreamTransport() { return streamTransport; }
    public String getNegotiatedCodec() { return negotiatedCodec; }
    public int getNegotiatedWidth() { return negotiatedWidth; }
    public int getNegotiatedHeight() { return negotiatedHeight; }
    public int getNegotiatedBitrate() { return negotiatedBitrate; }
    public int getNegotiatedFps() { return negotiatedFps; }
    public void setRequestedFps(int fps) { requestedFps = sanitizeFps(fps); }
    public void setNegotiatedFps(int fps) { negotiatedFps = sanitizeFps(fps); }

    private static int sanitizeFps(int fps) {
        return fps >= 120 ? 120 : (fps >= 60 ? 60 : 30);
    }
    public void setOnConnectionStateListener(OnConnectionStateListener l) {
        this.stateListener = l;
        if (l != null && pendingConnectedNotification
                && state == ConnectionState.CONNECTED && remoteDevice != null) {
            pendingConnectedNotification = false;
            l.onDeviceConnected(remoteDevice);
        }
    }

    public boolean isServerRunning() { return serverRunning.get(); }
}
