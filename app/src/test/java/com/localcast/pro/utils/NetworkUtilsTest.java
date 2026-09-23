package com.localcast.pro.utils;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NetworkUtilsTest {

    @Test
    public void acceptsValidIpv4Addresses() {
        assertTrue(NetworkUtils.isValidIp("192.168.1.10"));
        assertTrue(NetworkUtils.isValidIp("10.0.0.1"));
        assertTrue(NetworkUtils.isValidIp("255.255.255.255"));
    }

    @Test
    public void rejectsMalformedOrOutOfRangeAddresses() {
        assertFalse(NetworkUtils.isValidIp(null));
        assertFalse(NetworkUtils.isValidIp(""));
        assertFalse(NetworkUtils.isValidIp("192.168.1"));
        assertFalse(NetworkUtils.isValidIp("192.168.1.256"));
        assertFalse(NetworkUtils.isValidIp("receiver.local"));
    }
}
