package com.atakmap.android.LoRaBridge.phy;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * UdpManager
 *
 * Manages a unified UDP channel for SDR communication.
 *
 * Responsibilities:
 *   - Maintain a single RX port and single TX socket
 *   - Dispatch inbound packets to handlers based on message header:
 *       Chat messages      -> "SDR_CHAT|"
 *       CoT messages       -> "SDR_COT|"
 *       CoT fragments      -> "SDR_COTF|"
 *   - Provide async send API for chat and CoT messages
 *   - Lifecycle is controlled externally via start()/stop()
 *
 * Threading:
 *   - A dedicated thread performs blocking receive
 *   - A thread pool routes messages and handles async send
 *
 * This class is implemented as a thread-safe singleton.
 */
public class UdpManager {

    /** Functional interface for handlers receiving raw byte payloads */
    public interface ByteHandler { void accept(byte[] data); }

    /** Chat packet header prefix */
    public static final String HDR_CHAT = "SDR_CHAT|";

    /** CoT packet header prefix (complete message) */
    public static final String HDR_COT = "SDR_COT|";

    /** CoT fragment header prefix */
    public static final String HDR_COT_FRAG = "SDR_COTF|";

    private static final String TAG = "UdpManager";

    /** Singleton instance (thread-safe double-check) */
    private static volatile UdpManager INSTANCE;

    /** Get singleton instance */
    public static UdpManager getInstance() {
        if (INSTANCE == null) {
            synchronized (UdpManager.class) {
                if (INSTANCE == null) INSTANCE = new UdpManager();
            }
        }
        return INSTANCE;
    }

    /** Plugin receives UDP from SDR flowgraph via this port */
    private final int RX_PORT = 1383;

    /** Plugin sends UDP to the flowgraph via this port */
    private final int TX_PORT = 1382;

    /** Receiving and transmitting sockets */
    private DatagramSocket rxSocket;
    private DatagramSocket txSocket;

    /** Background thread running rxLoop() */
    private Thread receiveThread;

    /** Running flag for receive loop */
    private volatile boolean running = false;

    /** Optional message handlers */
    private volatile ByteHandler chatHandler;
    private volatile ByteHandler cotHandler;

    /** Optional host to mirror outgoing packets */
    private volatile String mirrorHost = null;

    /**
     * Thread pool used for:
     *   - Routing inbound messages
     *   - Async UDP sending
     */
    private final ExecutorService exec = new ThreadPoolExecutor(
            2, 4, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    private UdpManager() {}

    /** Register chat handler */
    public void setChatHandler(ByteHandler handler) { this.chatHandler = handler; }

    /** Register CoT handler (handles both complete and fragment messages) */
    public void setCotHandler(ByteHandler handler) { this.cotHandler = handler; }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Start UDP subsystem:
     *   - Bind receive socket
     *   - Create TX socket
     *   - Launch background receive thread
     */
    public synchronized void start() {
        if (running) return;
        try {
            rxSocket = new DatagramSocket(RX_PORT);
            txSocket = new DatagramSocket();
            running = true;

            receiveThread = new Thread(this::rxLoop, "Udp-Rx");
            receiveThread.start();

            Log.d(TAG, "UDP started on port " + RX_PORT);

        } catch (IOException e) {
            Log.e(TAG, "UDP start failed", e);
        }
    }

    /**
     * Stop UDP subsystem:
     *   - Stop loop
     *   - Close sockets
     *   - Interrupt thread
     *   - Shutdown executor
     */
    public synchronized void stop() {
        running = false;

        if (rxSocket != null) {
            rxSocket.close();
            rxSocket = null;
        }

        if (txSocket != null) {
            txSocket.close();
            txSocket = null;
        }

        if (receiveThread != null) {
            receiveThread.interrupt();
            try { receiveThread.join(500); } catch (InterruptedException ignored) {}
            receiveThread = null;
        }

        exec.shutdownNow();
        Log.d(TAG, "UDP stopped");
    }

    // =========================================================================
    // Public Send API
    // =========================================================================

    /**
     * Send chat payload with SDR_CHAT header.
     */
    public void sendChat(byte[] body) {
        if (body == null) return;
        byte[] payload = withHeader(HDR_CHAT, body);
        sendAsync(payload);
    }

    /**
     * Send CoT payload (already includes SDR_COT or SDR_COTF header).
     * This method sends the payload as-is without adding another header.
     */
    public void sendCot(byte[] body) {
        if (body == null) return;
        sendAsync(body);
    }

    /**
     * Set optional mirror host for debugging.
     */
    public void setMirrorHost(String hostOrNull) {
        this.mirrorHost = hostOrNull;
    }

    // =========================================================================
    // Receive Loop
    // =========================================================================

    /**
     * Blocking receive loop that runs in a dedicated thread.
     * For each received packet:
     *   - Copy payload to avoid array reuse
     *   - Submit routing to executor
     */
    private void rxLoop() {
        byte[] buf = new byte[4096];

        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                rxSocket.receive(p);

                byte[] data = Arrays.copyOf(p.getData(), p.getLength());
                exec.execute(() -> route(data));

            } catch (IOException e) {
                if (running)
                    Log.e(TAG, "UDP recv error", e);
            }
        }
    }

    // =========================================================================
    // Routing Logic
    // =========================================================================

    /**
     * Routes each payload by checking header prefix.
     */
    private void route(byte[] data) {
        try {
            Log.d(TAG, "route: received " + data.length + " bytes");

            String prefix = new String(
                    data,
                    0,
                    Math.min(data.length, 24),
                    StandardCharsets.UTF_8
            );

            Log.d(TAG, "route: prefix = [" + prefix.substring(0, Math.min(15, prefix.length())) + "]");

            // Complete CoT message
            if (prefix.startsWith(HDR_COT)) {
                Log.d(TAG, "route: detected SDR_COT message");
                if (cotHandler != null) {
                    cotHandler.accept(data);
                } else {
                    Log.w(TAG, "CoT payload but cotHandler == null, drop");
                }
                return;
            }

            // Fragmented CoT message
            if (prefix.startsWith(HDR_COT_FRAG)) {
                Log.d(TAG, "route: detected SDR_COTF fragment");
                if (cotHandler != null) {
                    cotHandler.accept(data);
                } else {
                    Log.w(TAG, "CoT fragment but cotHandler == null, drop");
                }
                return;
            }

            // Chat message
            if (prefix.startsWith(HDR_CHAT)) {
                Log.d(TAG, "route: detected SDR_CHAT message");
                if (chatHandler != null) {
                    chatHandler.accept(stripHeader(data, HDR_CHAT.length()));
                } else {
                    Log.w(TAG, "Chat payload but chatHandler == null, drop");
                }
                return;
            }

            Log.w(TAG, "Unknown UDP payload prefix, drop. prefix=" + prefix);

        } catch (Throwable t) {
            Log.e(TAG, "route failed", t);
        }
    }

    // =========================================================================
    // Internal Helpers
    // =========================================================================

    /** Strip header prefix from incoming packets */
    private static byte[] stripHeader(byte[] data, int headerLen) {
        int n = Math.max(0, data.length - headerLen);
        byte[] out = new byte[n];
        System.arraycopy(data, headerLen, out, 0, n);
        return out;
    }

    /** Submit UDP send to executor */
    private void sendAsync(byte[] payload) {
        exec.execute(() -> send(payload));
    }

    /**
     * Send payload to localhost:TX_PORT.
     * (Flowgraph listens there and forwards over SDR.)
     */
    private void send(byte[] payload) {
        if (payload == null || payload.length == 0)
            return;

        DatagramSocket sock = txSocket;

        if (sock == null || sock.isClosed()) {
            Log.w(TAG, "txSocket unavailable");
            return;
        }

        try {
            InetAddress localhost = InetAddress.getByName("127.0.0.1");
            sock.send(new DatagramPacket(payload, payload.length, localhost, TX_PORT));
            Log.d(TAG, "Sent " + payload.length + " bytes to localhost:" + TX_PORT);
        } catch (Exception e) {
            Log.e(TAG, "UDP send failed", e);
        }
    }

    /** Prepend header string to payload */
    private static byte[] withHeader(String header, byte[] body) {
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[h.length + body.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(body, 0, out, h.length, body.length);
        return out;
    }
}