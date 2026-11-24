package com.touchblocker;

import android.util.Log;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

public class AdbLocalClient {
    private static final String TAG = "AdbLocalClient";

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 4096;

    public static boolean executeCommand(String command) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", 5555), 2000);
            socket.setSoTimeout(3000);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // 1. Send CNXN packet
            byte[] identity = "host::\0".getBytes(StandardCharsets.UTF_8);
            sendPacket(out, A_CNXN, A_VERSION, MAX_PAYLOAD, identity);

            // 2. Read response header (24 bytes)
            byte[] header = readExact(in, 24);
            ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            int respCmd = hb.getInt();
            int pLen = hb.getInt(12);

            // Discard response payload
            if (pLen > 0) {
                readExact(in, pLen);
            }

            if (respCmd != A_CNXN) {
                Log.w(TAG, "Device did not accept CNXN directly (cmd=" + Integer.toHexString(respCmd) + ")");
            }

            // 3. Send OPEN packet for shell command
            byte[] destination = ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8);
            sendPacket(out, A_OPEN, 1, 0, destination);

            // Small delay to ensure adbd processes the packet
            Thread.sleep(150);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute ADB command: " + e.getMessage());
            return false;
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static void sendPacket(OutputStream out, int command, int arg0, int arg1, byte[] data) throws Exception {
        int length = data != null ? data.length : 0;
        int crc = 0;
        if (data != null) {
            for (byte b : data) {
                crc += (b & 0xFF);
            }
        }
        int magic = command ^ 0xFFFFFFFF;

        ByteBuffer buf = ByteBuffer.allocate(24 + length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(command);
        buf.putInt(arg0);
        buf.putInt(arg1);
        buf.putInt(length);
        buf.putInt(crc);
        buf.putInt(magic);
        if (data != null) {
            buf.put(data);
        }

        out.write(buf.array());
        out.flush();
    }

    private static byte[] readExact(InputStream in, int length) throws Exception {
        byte[] buf = new byte[length];
        int total = 0;
        while (total < length) {
            int read = in.read(buf, total, length - total);
            if (read < 0) {
                throw new java.io.EOFException("Unexpected end of stream");
            }
            total += read;
        }
        return buf;
    }
}
