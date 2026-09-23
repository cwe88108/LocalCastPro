package com.localcast.pro.core;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.util.Size;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * 设备信息模型
 * 用于设备发现和连接时交换设备信息
 */
public class DeviceInfo {

    public static final int TYPE_PHONE = 0;
    public static final int TYPE_TABLET = 1;
    public static final int TYPE_TV = 2;
    public static final int TYPE_BOX = 3;

    private String deviceId;        // 唯一设备标识
    private String deviceName;      // 设备名称
    private String ipAddress;       // IP地址
    private int deviceType;         // 设备类型
    private String model;           // 设备型号
    private String brand;           // 品牌
    private int apiLevel;           // Android API级别
    private boolean hevcSupported;  // 是否支持HEVC
    private int screenWidth;        // 屏幕宽度
    private int screenHeight;       // 屏幕高度
    private int tcpPort;            // TCP握手端口
    private int udpPort;            // UDP数据传输端口
    private long lastSeen;          // 最后发现时间
    private boolean isConnected;    // 是否已连接

    public DeviceInfo() {
        this.deviceId = UUID.randomUUID().toString();
        this.deviceName = Build.MODEL;
        this.model = Build.MODEL;
        this.brand = Build.BRAND;
        this.apiLevel = Build.VERSION.SDK_INT;
        this.lastSeen = System.currentTimeMillis();
    }

    /**
     * 使用Context生成稳定的设备ID
     */
    public void initDeviceId(Context context) {
        if (context != null) {
            String storedId = context.getSharedPreferences("localcast_device", Context.MODE_PRIVATE)
                    .getString("device_id", null);
            if (storedId != null && !storedId.isEmpty()) {
                this.deviceId = storedId;
                return;
            }
            String generatedId = UUID.randomUUID().toString();
            context.getSharedPreferences("localcast_device", Context.MODE_PRIVATE)
                    .edit().putString("device_id", generatedId).apply();
            this.deviceId = generatedId;
            return;
        }
        // 降级：使用UUID
        this.deviceId = UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * 判断是否为电视/盒子设备
     */
    public static boolean isTvDevice(Context context) {
        if (context != null) {
            int uiMode = context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_TYPE_MASK;
            return uiMode == Configuration.UI_MODE_TYPE_TELEVISION;
        }
        return false;
    }

    /**
     * 序列化为JSON（用于网络传输）
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        try {
            json.put("deviceId", deviceId);
            json.put("deviceName", deviceName);
            json.put("ipAddress", ipAddress != null ? ipAddress : "");
            json.put("deviceType", deviceType);
            json.put("model", model);
            json.put("brand", brand);
            json.put("apiLevel", apiLevel);
            json.put("hevcSupported", hevcSupported);
            json.put("screenWidth", screenWidth);
            json.put("screenHeight", screenHeight);
            json.put("tcpPort", tcpPort);
            json.put("udpPort", udpPort);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return json;
    }

    /**
     * 从JSON反序列化
     */
    public static DeviceInfo fromJson(JSONObject json) {
        DeviceInfo info = new DeviceInfo();
        info.deviceId = json.optString("deviceId", info.deviceId);
        info.deviceName = json.optString("deviceName", info.deviceName);
        info.ipAddress = json.optString("ipAddress", "");
        info.deviceType = json.optInt("deviceType", TYPE_PHONE);
        info.model = json.optString("model", "");
        info.brand = json.optString("brand", "");
        info.apiLevel = json.optInt("apiLevel", 24);
        info.hevcSupported = json.optBoolean("hevcSupported", false);
        info.screenWidth = json.optInt("screenWidth", 1920);
        info.screenHeight = json.optInt("screenHeight", 1080);
        info.tcpPort = json.optInt("tcpPort", 8889);
        info.udpPort = json.optInt("udpPort", 8889);
        return info;
    }

    /**
     * 更新设备在线状态
     */
    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }

    /**
     * 判断设备是否在线（30秒未响应视为离线）
     */
    public boolean isOnline() {
        return System.currentTimeMillis() - lastSeen < 30000;
    }

    /**
     * 获取设备类型显示名称
     */
    public String getDeviceTypeName() {
        switch (deviceType) {
            case TYPE_PHONE: return "手机";
            case TYPE_TABLET: return "平板";
            case TYPE_TV: return "电视";
            case TYPE_BOX: return "盒子";
            default: return "未知";
        }
    }

    /**
     * 获取编码器兼容性key（用于协商编码格式）
     */
    public String getCodecPreference() {
        return hevcSupported ? "hevc" : "avc";
    }

    public Size getScreenSize() {
        return new Size(screenWidth, screenHeight);
    }

    // ========== Getters & Setters ==========

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public int getDeviceType() { return deviceType; }
    public void setDeviceType(int deviceType) { this.deviceType = deviceType; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }

    public int getApiLevel() { return apiLevel; }
    public void setApiLevel(int apiLevel) { this.apiLevel = apiLevel; }

    public boolean isHevcSupported() { return hevcSupported; }
    public void setHevcSupported(boolean hevcSupported) { this.hevcSupported = hevcSupported; }

    public int getScreenWidth() { return screenWidth; }
    public void setScreenWidth(int screenWidth) { this.screenWidth = screenWidth; }

    public int getScreenHeight() { return screenHeight; }
    public void setScreenHeight(int screenHeight) { this.screenHeight = screenHeight; }

    public int getTcpPort() { return tcpPort; }
    public void setTcpPort(int tcpPort) { this.tcpPort = tcpPort; }

    public int getUdpPort() { return udpPort; }
    public void setUdpPort(int udpPort) { this.udpPort = udpPort; }

    public long getLastSeen() { return lastSeen; }

    public boolean isConnected() { return isConnected; }
    public void setConnected(boolean connected) { isConnected = connected; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DeviceInfo that = (DeviceInfo) o;
        return deviceId != null && deviceId.equals(that.deviceId);
    }

    @Override
    public int hashCode() {
        return deviceId != null ? deviceId.hashCode() : 0;
    }

    @Override
    public String toString() {
        return "DeviceInfo{" +
                "name='" + deviceName + '\'' +
                ", ip='" + ipAddress + '\'' +
                ", type=" + getDeviceTypeName() +
                ", hevc=" + hevcSupported +
                '}';
    }
}
