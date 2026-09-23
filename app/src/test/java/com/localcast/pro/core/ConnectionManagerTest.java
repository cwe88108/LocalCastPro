package com.localcast.pro.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;

public class ConnectionManagerTest {

    @Test
    public void acceptsTerminatedHandshake() throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader("{\"command\":\"CONNECT_REQUEST\"}\r\n"));
        assertEquals("{\"command\":\"CONNECT_REQUEST\"}", ConnectionManager.readLimitedLine(reader));
    }

    @Test(expected = IOException.class)
    public void rejectsOversizedHandshake() throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader("x".repeat(16 * 1024 + 1) + "\n"));
        ConnectionManager.readLimitedLine(reader);
    }

    @Test(expected = IOException.class)
    public void rejectsUnterminatedHandshake() throws IOException {
        BufferedReader reader = new BufferedReader(new StringReader("{\"command\":\"CONNECT_REQUEST\"}"));
        ConnectionManager.readLimitedLine(reader);
    }
}
