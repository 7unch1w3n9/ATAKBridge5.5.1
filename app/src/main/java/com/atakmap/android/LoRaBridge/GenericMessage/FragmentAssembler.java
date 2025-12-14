package com.atakmap.android.LoRaBridge.GenericMessage;

import android.os.Build;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.CRC32;

/**
 * FragmentAssembler
 *
 * Manages reassembly of fragmented SDR messages.
 *
 * When messages exceed the SDR payload limit (e.g., 255 bytes for LoRa),
 * the sender splits them into multiple fragments. This class collects
 * all fragments and reassembles the original message when complete.
 *
 * Features:
 *   - CRC16 checksum validation per fragment
 *   - Automatic timeout and cleanup of incomplete messages
 *   - Thread-safe operation
 */
public class FragmentAssembler {

    private static final String TAG = "FragmentAssembler";

    /** Fragment timeout in milliseconds */
    private static final long FRAGMENT_TIMEOUT_MS = 30000;  // 30 seconds

    /** Pending messages awaiting completion */
    private final Map<String, PendingMessage> pending = new ConcurrentHashMap<>();

    /**
     * Represents a message being assembled from fragments.
     */
    private static class PendingMessage {
        final String msgId;
        final int total;
        final String[] fragments;
        final long createTime;
        int receivedCount = 0;

        PendingMessage(String msgId, int total) {
            this.msgId = msgId;
            this.total = total;
            this.fragments = new String[total];
            this.createTime = System.currentTimeMillis();
        }

        boolean isComplete() {
            return receivedCount == total;
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createTime > FRAGMENT_TIMEOUT_MS;
        }

        String assemble() {
            StringBuilder sb = new StringBuilder();
            for (String frag : fragments) {
                if (frag != null) sb.append(frag);
            }
            return sb.toString();
        }
    }

    /**
     * Add a fragment to the assembler.
     *
     * @param msgId    Unique message identifier
     * @param seq      Fragment sequence number (0-based)
     * @param total    Total number of fragments
     * @param checksum CRC16 checksum (4 hex characters)
     * @param data     Fragment payload data
     * @return Assembled message if complete, null otherwise
     */
    public synchronized String addFragment(String msgId, int seq, int total,
                                           String checksum, String data) {

        // Validate checksum
        String computed = computeChecksum(data);
        if (!computed.equals(checksum)) {
            Log.e(TAG, "Checksum mismatch for " + msgId + " seq=" + seq +
                    ": expected=" + checksum + ", computed=" + computed);
            return null;
        }

        // Cleanup expired messages
        cleanupExpired();

        // Get or create PendingMessage
        PendingMessage pm = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pm = pending.computeIfAbsent(msgId,
                    k -> new PendingMessage(msgId, total));
        }

        // Verify total count consistency
        if (pm.total != total) {
            Log.e(TAG, "Total mismatch for " + msgId + ": existing=" + pm.total +
                    ", received=" + total);
            return null;
        }

        // Validate sequence number
        if (seq < 0 || seq >= total) {
            Log.e(TAG, "Invalid seq=" + seq + " for total=" + total);
            return null;
        }

        // Store fragment (ignore duplicates)
        if (pm.fragments[seq] == null) {
            pm.fragments[seq] = data;
            pm.receivedCount++;
            Log.d(TAG, "Fragment " + msgId + " [" + (seq + 1) + "/" + total + "] received, " +
                    "progress: " + pm.receivedCount + "/" + total);
        } else {
            Log.d(TAG, "Duplicate fragment " + msgId + " seq=" + seq + ", ignored");
        }

        // Check for completion
        if (pm.isComplete()) {
            pending.remove(msgId);
            String assembled = pm.assemble();
            Log.d(TAG, "Message " + msgId + " complete, assembled " + assembled.length() + " chars");
            return assembled;
        }

        return null;
    }

    /**
     * Compute CRC16 checksum as 4 hex characters.
     *
     * @param data Input string
     * @return 4-character hex string representing lower 16 bits of CRC32
     */
    public static String computeChecksum(String data) {
        CRC32 crc = new CRC32();
        crc.update(data.getBytes(StandardCharsets.UTF_8));
        return String.format("%04x", (int) (crc.getValue() & 0xFFFF));
    }

    /**
     * Remove expired pending messages.
     */
    private void cleanupExpired() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            pending.entrySet().removeIf(entry -> {
                if (entry.getValue().isExpired()) {
                    Log.w(TAG, "Message " + entry.getKey() + " expired, discarding " +
                            entry.getValue().receivedCount + "/" + entry.getValue().total + " fragments");
                    return true;
                }
                return false;
            });
        }
    }

    /**
     * Get count of pending incomplete messages (for debugging).
     */
    public int getPendingCount() {
        return pending.size();
    }
}
