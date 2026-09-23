package com.localcast.pro.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * 网络工具类
 * 获取本机IP地址、判断网络状态、网络相关操作
 */
public class NetworkUtils {

    /**
     * 获取本机WiFi局域网IPv4地址
     */
    public static String getLocalIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                // 过滤回环和未启用的接口
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    // 只获取IPv4地址，排除IPv6
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String ip = address.getHostAddress();
                        // 过滤非局域网IP（通常是192.168.x.x 或 10.x.x.x 或 172.16-31.x.x）
                        if (isPrivateIp(ip)) {
                            return ip;
                        }
                    }
                }
            }
        } catch (SocketException e) {
            Logger.e("NetworkUtils", "Failed to get local IP", e);
        }
        return "0.0.0.0";
    }

    /**
     * 判断是否为私有局域网IP
     */
    private static boolean isPrivateIp(String ip) {
        if (ip == null) return false;
        try {
            String[] parts = ip.split("\\.");
            if (parts.length != 4) return false;
            int first = Integer.parseInt(parts[0]);
            int second = Integer.parseInt(parts[1]);

            // 10.x.x.x
            if (first == 10) return true;
            // 172.16.x.x - 172.31.x.x
            if (first == 172 && second >= 16 && second <= 31) return true;
            // 192.168.x.x
            if (first == 192 && second == 168) return true;

            return false;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 获取WiFi广播地址
     */
    public static String getBroadcastAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
            if (dhcpInfo != null) {
                return intToIp(dhcpInfo.ipAddress | ~dhcpInfo.netmask);
            }
        }
        return "255.255.255.255";
    }

    /**
     * 获取网关地址
     */
    public static String getGatewayAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
            if (dhcpInfo != null) {
                return intToIp(dhcpInfo.gateway);
            }
        }
        return null;
    }

    /**
     * 判断当前是否连接WiFi
     */
    public static boolean isWifiConnected(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities capabilities = cm.getNetworkCapabilities(network);
            return capabilities != null &&
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } else {
            NetworkInfo networkInfo = cm.getActiveNetworkInfo();
            return networkInfo != null && networkInfo.isConnected() &&
                    networkInfo.getType() == ConnectivityManager.TYPE_WIFI;
        }
    }

    /**
     * 获取WiFi SSID
     */
    public static String getWifiSSID(Context context) {
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo != null) {
                String ssid = wifiInfo.getSSID();
                // 去掉引号
                if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                    ssid = ssid.substring(1, ssid.length() - 1);
                }
                return ssid;
            }
        }
        return null;
    }

    /**
     * int IP 转 String
     */
    private static String intToIp(int ip) {
        return (ip & 0xFF) + "." +
                ((ip >> 8) & 0xFF) + "." +
                ((ip >> 16) & 0xFF) + "." +
                ((ip >> 24) & 0xFF);
    }

    /**
     * 验证IP地址格式
     */
    public static boolean isValidIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        String pattern = "^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$";
        return ip.matches(pattern);
    }
}
