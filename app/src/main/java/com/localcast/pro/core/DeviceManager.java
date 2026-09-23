package com.localcast.pro.core;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import com.localcast.pro.utils.Logger;
import com.localcast.pro.utils.NetworkUtils;

import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 设备发现管理器
 * 
 * 双通道发现机制：
 * 1. 主通道：mDNS/Bonjour (NsdManager) - 自动、高效
 * 2. 备用通道：UDP广播 (255.255.255.255:8888) - 兜底、兼容
 * 
 * 每5秒刷新一次设备列表，30秒未响应设备自动移除
 */
public class DeviceManager {

    private static final String TAG = "DeviceManager";

    // mDNS服务信息
    private static final String SERVICE_TYPE = "_localcast._tcp.local.";
    private static final String SERVICE_NAME_PREFIX = "LocalCastPro-";

    // UDP广播
    private static final int BROADCAST_PORT = 8888;
    private static final String BROADCAST_MESSAGE = "LOCALCAST_DISCOVER";
    private static final String BROADCAST_RESPONSE = "LOCALCAST_HERE";

    // 刷新间隔
    private static final long REFRESH_INTERVAL_MS = 5000;
    private static final long DEVICE_TIMEOUT_MS = 30000;

    /** Only a device with an active TCP receiver may answer discovery probes. */
    private static volatile boolean receiverAvailable = false;

    private Context context;
    private NsdManager nsdManager;
    private NsdManager.RegistrationListener registrationListener;
    private NsdManager.DiscoveryListener discoveryListener;

    // 广播
    private DatagramSocket broadcastSocket;
    private HandlerThread broadcastThread;
    private Handler broadcastHandler;
    private volatile boolean broadcastRunning = false;

    // 设备列表（线程安全）
    private final Map<String, DeviceInfo> deviceMap = new ConcurrentHashMap<>();
    private final List<DeviceInfo> deviceList = new CopyOnWriteArrayList<>();

    // 回调
    private final List<OnDeviceDiscoveryListener> listeners = new CopyOnWriteArrayList<>();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    // ========== 回调接口 ==========

    public interface OnDeviceDiscoveryListener {
        void onDeviceFound(DeviceInfo device);
        void onDeviceLost(DeviceInfo device);
        void onDeviceUpdated(DeviceInfo device);
        void onDiscoveryError(String error);
    }

    // ========== 公共方法 ==========

    /**
     * 初始化
     */
    public void init(Context context) {
        this.context = context.getApplicationContext();
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
    }

    public static void setReceiverAvailable(boolean available) {
        receiverAvailable = available;
    }

    /**
     * 注册本机mDNS服务（让其他设备能发现本机）
     */
    public void registerService(DeviceInfo localDevice) {
        if (nsdManager == null) {
            Logger.e(TAG, "NsdManager is null");
            return;
        }

        NsdServiceInfo serviceInfo = new NsdServiceInfo();
        serviceInfo.setServiceName(SERVICE_NAME_PREFIX + localDevice.getDeviceId());
        serviceInfo.setServiceType(SERVICE_TYPE);
        serviceInfo.setPort(ConnectionManager.TCP_PORT);

        // 设置设备信息为TXT记录
        try {
            JSONObject json = localDevice.toJson();
            Map<String, String> txtMap = new java.util.HashMap<>();
            txtMap.put("info", json.toString());
            serviceInfo.setAttribute("info", json.toString());
        } catch (Exception e) {
            Logger.e(TAG, "Failed to set service attributes", e);
        }

        registrationListener = new NsdManager.RegistrationListener() {
            @Override
            public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Logger.e(TAG, "mDNS registration failed: " + errorCode);
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Logger.e(TAG, "mDNS unregistration failed: " + errorCode);
            }

            @Override
            public void onServiceRegistered(NsdServiceInfo serviceInfo) {
                Logger.i(TAG, "mDNS service registered: " + serviceInfo.getServiceName());
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo serviceInfo) {
                Logger.i(TAG, "mDNS service unregistered");
            }
        };

        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
    }

    /**
     * 取消注册mDNS服务
     */
    public void unregisterService() {
        if (nsdManager != null && registrationListener != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception e) {
                Logger.e(TAG, "Failed to unregister mDNS service", e);
            }
        }
    }

    /**
     * 开始发现设备（mDNS + UDP广播双通道）
     */
    public void startDiscovery() {
        startNsdDiscovery();
        startBroadcastDiscovery();
        Logger.i(TAG, "Device discovery started (mDNS + UDP broadcast)");
    }

    /**
     * 停止发现设备
     */
    public void stopDiscovery() {
        stopNsdDiscovery();
        stopBroadcastDiscovery();
        Logger.i(TAG, "Device discovery stopped");
    }

    /**
     * 手动添加设备（通过IP地址）
     */
    public void addManualDevice(String ipAddress) {
        if (!NetworkUtils.isValidIp(ipAddress)) {
            notifyError("无效的IP地址: " + ipAddress);
            return;
        }

        DeviceInfo device = new DeviceInfo();
        device.setIpAddress(ipAddress);
        device.setDeviceName("手动设备-" + ipAddress);
        device.setTcpPort(ConnectionManager.TCP_PORT);
        device.setUdpPort(ConnectionManager.TCP_PORT);
        device.updateLastSeen();

        addOrUpdateDevice(device);

        // 尝试连接验证设备
        verifyDevice(device);
    }

    /**
     * 获取设备列表
     */
    public List<DeviceInfo> getDeviceList() {
        deviceList.clear();
        deviceList.addAll(deviceMap.values());
        return deviceList;
    }

    /**
     * 刷新设备列表（移除离线设备）
     */
    public void refreshDevices() {
        List<String> offlineDevices = new ArrayList<>();
        for (Map.Entry<String, DeviceInfo> entry : deviceMap.entrySet()) {
            if (!entry.getValue().isOnline()) {
                offlineDevices.add(entry.getKey());
            }
        }
        for (String key : offlineDevices) {
            DeviceInfo removed = deviceMap.remove(key);
            if (removed != null) {
                notifyDeviceLost(removed);
            }
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        stopDiscovery();
        unregisterService();
        listeners.clear();
    }

    // ========== mDNS发现 ==========

    private void startNsdDiscovery() {
        if (nsdManager == null) return;

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Logger.e(TAG, "mDNS discovery start failed: " + errorCode);
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Logger.e(TAG, "mDNS discovery stop failed: " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Logger.i(TAG, "mDNS discovery started");
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Logger.i(TAG, "mDNS discovery stopped");
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                String serviceName = serviceInfo.getServiceName();
                // 过滤自己的服务
                if (serviceName.startsWith(SERVICE_NAME_PREFIX)) {
                    String deviceId = serviceName.substring(SERVICE_NAME_PREFIX.length());
                    // 不发现自己
                    DeviceInfo local = getLocalDeviceInfo();
                    if (local != null && deviceId.equals(local.getDeviceId())) {
                        return;
                    }
                }
                // 解析服务
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        Logger.w(TAG, "mDNS resolve failed: " + errorCode);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo serviceInfo) {
                        handleResolvedService(serviceInfo);
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                String serviceName = serviceInfo.getServiceName();
                if (serviceName.startsWith(SERVICE_NAME_PREFIX)) {
                    String deviceId = serviceName.substring(SERVICE_NAME_PREFIX.length());
                    DeviceInfo device = deviceMap.get(deviceId);
                    if (device != null) {
                        deviceMap.remove(deviceId);
                        notifyDeviceLost(device);
                    }
                }
            }
        };

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    private void stopNsdDiscovery() {
        if (nsdManager != null && discoveryListener != null) {
            try {
                nsdManager.stopServiceDiscovery(discoveryListener);
            } catch (Exception e) {
                Logger.e(TAG, "Failed to stop mDNS discovery", e);
            }
        }
    }

    private void handleResolvedService(NsdServiceInfo serviceInfo) {
        try {
            DeviceInfo device = new DeviceInfo();
            device.setIpAddress(serviceInfo.getHost().getHostAddress());
            device.setTcpPort(serviceInfo.getPort());
            device.setUdpPort(ConnectionManager.TCP_PORT);
            device.updateLastSeen();

            // 尝试从TXT记录获取设备信息
            String infoJson = null;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                Map<String, byte[]> attributes = serviceInfo.getAttributes();
                if (attributes != null && attributes.containsKey("info")) {
                    infoJson = new String(attributes.get("info"));
                }
            }

            if (infoJson != null && !infoJson.isEmpty()) {
                DeviceInfo parsed = DeviceInfo.fromJson(new JSONObject(infoJson));
                parsed.setIpAddress(device.getIpAddress());
                parsed.setTcpPort(device.getTcpPort());
                parsed.updateLastSeen();
                addOrUpdateDevice(parsed);
            } else {
                addOrUpdateDevice(device);
            }
        } catch (Exception e) {
            Logger.e(TAG, "Failed to handle resolved service", e);
        }
    }

    // ========== UDP广播发现 ==========

    private void startBroadcastDiscovery() {
        broadcastThread = new HandlerThread("BroadcastDiscovery");
        broadcastThread.start();
        broadcastHandler = new Handler(broadcastThread.getLooper());

        broadcastRunning = true;

        broadcastHandler.post(() -> {
            try {
                broadcastSocket = new DatagramSocket(null);
                broadcastSocket.setReuseAddress(true);
                broadcastSocket.bind(new InetSocketAddress(BROADCAST_PORT));
                broadcastSocket.setBroadcast(true);

                // 启动接收线程
                new Thread(this::broadcastReceiveLoop, "BroadcastReceive").start();

                // 立即发送第一次广播，然后定期发送
                sendBroadcast();
                broadcastHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!broadcastRunning) return;
                        sendBroadcast();
                        broadcastHandler.postDelayed(this, REFRESH_INTERVAL_MS);
                    }
                }, REFRESH_INTERVAL_MS);

            } catch (IOException e) {
                Logger.e(TAG, "Failed to start broadcast discovery", e);
                notifyError("UDP广播发现启动失败");
            }
        });
    }

    private void stopBroadcastDiscovery() {
        broadcastRunning = false;
        if (broadcastSocket != null && !broadcastSocket.isClosed()) {
            broadcastSocket.close();
        }
        if (broadcastThread != null) {
            broadcastThread.quitSafely();
        }
    }

    private void sendBroadcast() {
        try {
            String broadcastAddr = NetworkUtils.getBroadcastAddress(context);
            byte[] data = BROADCAST_MESSAGE.getBytes();
            DatagramPacket packet = new DatagramPacket(
                    data, data.length,
                    InetAddress.getByName(broadcastAddr), BROADCAST_PORT
            );
            broadcastSocket.send(packet);
        } catch (IOException e) {
            Logger.e(TAG, "Broadcast send failed", e);
        }
    }

    private void broadcastReceiveLoop() {
        byte[] buffer = new byte[1024];
        while (broadcastRunning) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                broadcastSocket.receive(packet);

                String message = new String(packet.getData(), 0, packet.getLength());
                String senderIp = packet.getAddress().getHostAddress();

                // 忽略自己的广播
                String localIp = NetworkUtils.getLocalIpAddress();
                if (localIp.equals(senderIp)) continue;

                if (BROADCAST_MESSAGE.equals(message) && receiverAvailable) {
                    // 收到发现请求，回复本机信息
                    sendBroadcastResponse(packet.getAddress());
                } else if (message.startsWith(BROADCAST_RESPONSE)) {
                    // 收到发现响应，解析设备信息
                    handleBroadcastResponse(senderIp, message);
                }

            } catch (IOException e) {
                if (broadcastRunning) {
                    Logger.e(TAG, "Broadcast receive error", e);
                }
            }
        }
    }

    private void sendBroadcastResponse(InetAddress targetAddress) {
        try {
            DeviceInfo local = getLocalDeviceInfo();
            String response;
            if (local != null) {
                response = BROADCAST_RESPONSE + ":" + local.toJson().toString();
            } else {
                response = BROADCAST_RESPONSE + ":{}";
            }
            byte[] data = response.getBytes();
            DatagramPacket packet = new DatagramPacket(
                    data, data.length, targetAddress, BROADCAST_PORT
            );
            broadcastSocket.send(packet);
        } catch (IOException e) {
            Logger.e(TAG, "Broadcast response failed", e);
        }
    }

    private void handleBroadcastResponse(String ip, String message) {
        try {
            int colonIndex = message.indexOf(':');
            if (colonIndex < 0) return;

            String jsonStr = message.substring(colonIndex + 1);
            if (jsonStr.isEmpty() || "{}".equals(jsonStr)) return;

            DeviceInfo device = DeviceInfo.fromJson(new JSONObject(jsonStr));
            device.setIpAddress(ip);
            device.setTcpPort(ConnectionManager.TCP_PORT);
            device.setUdpPort(ConnectionManager.TCP_PORT);
            device.updateLastSeen();

            addOrUpdateDevice(device);
        } catch (Exception e) {
            Logger.e(TAG, "Failed to parse broadcast response", e);
        }
    }

    // ========== 设备管理 ==========

    private void addOrUpdateDevice(DeviceInfo device) {
        String key = device.getDeviceId();
        DeviceInfo existing = deviceMap.get(key);

        if (existing != null) {
            // 更新IP等信息
            existing.setIpAddress(device.getIpAddress());
            existing.setTcpPort(device.getTcpPort());
            existing.setUdpPort(device.getUdpPort());
            existing.updateLastSeen();
            notifyDeviceUpdated(existing);
        } else {
            deviceMap.put(key, device);
            notifyDeviceFound(device);
        }
    }

    private void verifyDevice(DeviceInfo device) {
        new Thread(() -> {
            try {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new InetSocketAddress(
                        device.getIpAddress(), device.getTcpPort()), 3000);
                socket.close();
                device.updateLastSeen();
                addOrUpdateDevice(device);
            } catch (IOException e) {
                deviceMap.remove(device.getDeviceId());
                notifyDeviceLost(device);
            }
        }, "DeviceVerify").start();
    }

    private DeviceInfo localDeviceInfo;

    /**
     * 设置本地设备信息（必须在 startDiscovery 之前调用）
     */
    public void setLocalDeviceInfo(DeviceInfo info) {
        this.localDeviceInfo = info;
    }

    private DeviceInfo getLocalDeviceInfo() {
        return localDeviceInfo;
    }

    // ========== 通知 ==========

    private void notifyDeviceFound(DeviceInfo device) {
        mainHandler.post(() -> {
            for (OnDeviceDiscoveryListener listener : listeners) {
                listener.onDeviceFound(device);
            }
        });
    }

    private void notifyDeviceLost(DeviceInfo device) {
        mainHandler.post(() -> {
            for (OnDeviceDiscoveryListener listener : listeners) {
                listener.onDeviceLost(device);
            }
        });
    }

    private void notifyDeviceUpdated(DeviceInfo device) {
        mainHandler.post(() -> {
            for (OnDeviceDiscoveryListener listener : listeners) {
                listener.onDeviceUpdated(device);
            }
        });
    }

    private void notifyError(String error) {
        mainHandler.post(() -> {
            for (OnDeviceDiscoveryListener listener : listeners) {
                listener.onDiscoveryError(error);
            }
        });
    }

    // ========== 监听器管理 ==========

    public void addListener(OnDeviceDiscoveryListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(OnDeviceDiscoveryListener listener) {
        listeners.remove(listener);
    }
}
