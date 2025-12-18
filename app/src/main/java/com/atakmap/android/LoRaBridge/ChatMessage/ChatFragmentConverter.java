package com.atakmap.android.LoRaBridge.ChatMessage;

import android.annotation.SuppressLint;
import android.util.Log;

import com.atakmap.android.LoRaBridge.Database.ChatMessageEntity;
import com.atakmap.android.LoRaBridge.GenericMessage.FragmentAssembler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * ChatFragmentConverter
 *
 * Converts ChatMessageEntity to/from wire format with automatic fragmentation.
 *
 * Wire Formats:
 *
 *   Complete message (small payload <= 236 bytes):
 *     SDR_CHAT|<pipe_delimited_fields>
 *
 *   Fragmented message (large payload > 236 bytes):
 *     SDR_CHATF|msg_id|seq|total|checksum|<chunk>
 *
 * Features:
 *   - Automatic fragmentation for messages exceeding payload limit
 *   - CRC16 checksum per fragment for integrity
 *   - Reassembly of fragmented messages
 */
public class ChatFragmentConverter {

    private static final String TAG = "ChatFragmentConverter";

    /** Complete message prefix */
    public static final String PREFIX_FULL = "SDR_CHAT|";

    /** Fragment message prefix */
    public static final String PREFIX_FRAG = "SDR_CHATF|";

    /** Maximum SDR payload size (LoRa SF7 = 255 bytes, with margin) */
    private static final int MAX_SDR_PAYLOAD = 245;

    /** Maximum payload size for single message (payload - prefix length) */
    private static final int MAX_SINGLE_PAYLOAD = MAX_SDR_PAYLOAD - PREFIX_FULL.length();

    /** Maximum data per fragment (leaving room for header ~30 bytes) */
    private static final int MAX_FRAGMENT_DATA = 200;

    /** Base converter for pipe-delimited encoding */
    private final LoRaMessageConverter baseConverter = new LoRaMessageConverter();

    /** Fragment reassembler */
    private final FragmentAssembler assembler = new FragmentAssembler();

    // =========================================================================
    // Encoding - ChatMessageEntity to wire format
    // =========================================================================

    /**
     * Encode ChatMessageEntity with automatic fragmentation if needed.
     *
     * @param message ChatMessageEntity to encode
     * @return List of byte arrays to send (1 for small, multiple for large)
     */
    public List<byte[]> encode(ChatMessageEntity message) {
        List<byte[]> result = new ArrayList<>();

        if (message == null) {
            Log.w(TAG, "encode: null message");
            return result;
        }

        // Use base converter to get pipe-delimited format
        byte[] encoded = baseConverter.encode(message);

        if (encoded == null || encoded.length == 0) {
            Log.w(TAG, "encode: empty encoded payload");
            return result;
        }

        if (encoded.length <= MAX_SINGLE_PAYLOAD) {
            // Small enough - send as single message
            byte[] payload = withPrefix(PREFIX_FULL, encoded);
            result.add(payload);
            Log.d(TAG, "encode: single message " + payload.length + " bytes");
        } else {
            // Too large - fragment it
            result = createFragments(encoded, message.getId());
            Log.d(TAG, "encode: created " + result.size() + " fragments for "
                    + encoded.length + " bytes payload");
        }

        return result;
    }

    /**
     * Create fragments for large payload.
     *
     * Fragment format: SDR_CHATF|msg_id|seq|total|checksum|<chunk>
     *
     * @param data Complete encoded payload
     * @param messageId Message identifier for fragment tracking
     * @return List of fragment byte arrays
     */
    private List<byte[]> createFragments(byte[] data, String messageId) {
        List<byte[]> fragments = new ArrayList<>();

        // Calculate number of fragments needed
        int totalFragments = (int) Math.ceil((double) data.length / MAX_FRAGMENT_DATA);

        // Use provided messageId or generate one (8 hex chars from timestamp)
        String fragId = (messageId != null && !messageId.isEmpty())
                ? messageId.substring(0, Math.min(8, messageId.length()))
                : Long.toHexString(System.currentTimeMillis()).substring(0, 8);

        // Split data into chunks
        for (int i = 0; i < totalFragments; i++) {
            int start = i * MAX_FRAGMENT_DATA;
            int end = Math.min(start + MAX_FRAGMENT_DATA, data.length);
            int chunkLen = end - start;

            // Extract chunk as string
            String chunk = new String(data, start, chunkLen, StandardCharsets.UTF_8);

            // Calculate checksum for this chunk (4 hex characters)
            String checksum = FragmentAssembler.computeChecksum(chunk);

            // Build fragment header: SDR_CHATF|msg_id|seq|total|checksum|
            @SuppressLint("DefaultLocale") String header = String.format("%s%s|%d|%d|%s|",
                    PREFIX_FRAG,
                    fragId,
                    i,
                    totalFragments,
                    checksum
            );

            // Combine header + chunk into complete fragment
            String fragment = header + chunk;
            byte[] fragmentBytes = fragment.getBytes(StandardCharsets.UTF_8);

            fragments.add(fragmentBytes);

            Log.d(TAG, String.format("Fragment %d/%d: %d bytes (chunk=%d, checksum=%s)",
                    i, totalFragments, fragmentBytes.length, chunkLen, checksum));
        }

        return fragments;
    }

    // =========================================================================
    // Decoding - Wire format to ChatMessageEntity
    // =========================================================================

    /**
     * Decode raw bytes from PHY layer into ChatMessageEntity.
     *
     * For fragmented messages, this may return null until all fragments
     * are received and reassembled.
     *
     * @param payload Raw bytes received from SDR (includes SDR_CHAT or SDR_CHATF prefix)
     * @return Decoded entity, or null if decode failed or awaiting fragments
     */
    public ChatMessageEntity decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            Log.w(TAG, "decode: empty payload");
            return null;
        }

        String prefix = new String(
                payload,
                0,
                Math.min(payload.length, 20),
                StandardCharsets.UTF_8
        );

        // Complete message
        if (prefix.startsWith(PREFIX_FULL)) {
            byte[] body = stripPrefix(payload, PREFIX_FULL.length());
            return baseConverter.decode(body);
        }

        // Fragmented message
        if (prefix.startsWith(PREFIX_FRAG)) {
            return decodeFragment(payload);
        }

        Log.w(TAG, "decode: unknown prefix: " + prefix);
        return null;
    }

    /**
     * Decode a single fragment and attempt reassembly.
     *
     * @param payload Fragment payload (SDR_CHATF|msg_id|seq|total|checksum|chunk)
     * @return Complete ChatMessageEntity if all fragments received, null otherwise
     */
    private ChatMessageEntity decodeFragment(byte[] payload) {
        try {
            // Strip prefix
            byte[] body = stripPrefix(payload, PREFIX_FRAG.length());
            String bodyStr = new String(body, StandardCharsets.UTF_8);

            // Parse fragment header: msg_id|seq|total|checksum|<chunk>
            String[] parts = bodyStr.split("\\|", 5);
            if (parts.length < 5) {
                Log.w(TAG, "decodeFragment: malformed header, parts=" + parts.length);
                return null;
            }

            String msgId = parts[0];
            int seq = Integer.parseInt(parts[1]);
            int total = Integer.parseInt(parts[2]);
            String checksum = parts[3];  // 4 hex characters
            String chunk = parts[4];

            Log.d(TAG, String.format("decodeFragment: %s[%d/%d] checksum=%s",
                    msgId, seq, total, checksum));

            // Add fragment to assembler (checksum validation happens inside)
            String completeData = assembler.addFragment(msgId, seq, total, checksum, chunk);

            if (completeData == null) {
                // Still waiting for more fragments
                return null;
            }

            // All fragments received - decode complete message
            Log.d(TAG, "decodeFragment: reassembly complete for " + msgId
                    + ", total=" + completeData.length() + " bytes");

            byte[] completeBytes = completeData.getBytes(StandardCharsets.UTF_8);
            return baseConverter.decode(completeBytes);

        } catch (Exception e) {
            Log.e(TAG, "decodeFragment: parse error", e);
            return null;
        }
    }

    // =========================================================================
    // Utility Methods
    // =========================================================================

    /**
     * Prepend prefix to payload.
     */
    private static byte[] withPrefix(String prefix, byte[] body) {
        byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[p.length + body.length];
        System.arraycopy(p, 0, out, 0, p.length);
        System.arraycopy(body, 0, out, p.length, body.length);
        return out;
    }

    /**
     * Strip prefix from payload.
     */
    private static byte[] stripPrefix(byte[] data, int prefixLen) {
        int n = Math.max(0, data.length - prefixLen);
        byte[] out = new byte[n];
        System.arraycopy(data, prefixLen, out, 0, n);
        return out;
    }
}