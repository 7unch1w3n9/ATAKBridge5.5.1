package com.atakmap.android.LoRaBridge.GenericMessage;

import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * SdrCotConverter
 *
 * Converts CoT XML to/from SDR wire format with automatic fragmentation.
 *
 * Wire Formats:
 *
 *   Complete message (small XML <= 237 bytes):
 *     SDR_COT|<complete_xml>
 *
 *   Fragmented message (large XML > 237 bytes):
 *     SDR_COTF|msg_id|seq|total|checksum|<xml_chunk>
 *
 * Features:
 *   - Automatic fragmentation for XML exceeding payload limit
 *   - CRC16 checksum per fragment for integrity
 *   - Reassembly of fragmented messages
 */
public class SdrCotConverter {

    private static final String TAG = "SdrCotConverter";

    /** Complete message prefix */
    public static final String PREFIX_FULL = "SDR_COT|";

    /** Fragment message prefix */
    public static final String PREFIX_FRAG = "SDR_COTF|";

    /** Maximum SDR payload size (LoRa SF7 = 255 bytes, with margin) */
    private static final int MAX_SDR_PAYLOAD = 245;

    /** Maximum XML size for single message (payload - prefix length) */
    private static final int MAX_SINGLE_XML = MAX_SDR_PAYLOAD - PREFIX_FULL.length();

    /** Maximum data per fragment (leaving room for header ~30 bytes) */
    private static final int MAX_FRAGMENT_DATA = 200;

    /** Fragment reassembler */
    private final FragmentAssembler assembler = new FragmentAssembler();

    // =========================================================================
    // Encoding - XML to wire format
    // =========================================================================

    /**
     * Encode XML with automatic fragmentation if needed.
     *
     * @param xml Complete CoT XML string
     * @return List of byte arrays to send (1 for small, multiple for large)
     */
    public List<byte[]> encode(String xml) {
        List<byte[]> result = new ArrayList<>();

        if (xml == null || xml.isEmpty()) {
            Log.w(TAG, "encode: empty XML");
            return result;
        }

        // Minify XML to save space
        String minified = minifyXml(xml);

        if (minified.length() <= MAX_SINGLE_XML) {
            // Small enough - send as single message
            String message = PREFIX_FULL + minified;
            result.add(message.getBytes(StandardCharsets.UTF_8));
            Log.d(TAG, "encode: single message " + message.length() + " bytes");
        } else {
            // Too large - fragment it
            result = createFragments(minified);
            Log.d(TAG, "encode: created " + result.size() + " fragments for " + minified.length() + " bytes XML");
        }

        return result;
    }

    /**
     * Create fragments for large XML.
     * Format: SDR_COTF|msg_id|seq|total|checksum|data
     */
    private List<byte[]> createFragments(String xml) {
        List<byte[]> fragments = new ArrayList<>();

        // Generate short message ID (8 hex chars from timestamp)
        String msgId = Long.toHexString(System.currentTimeMillis()).substring(0, 8);

        // Calculate fragment count
        int total = (int) Math.ceil((double) xml.length() / MAX_FRAGMENT_DATA);

        for (int seq = 0; seq < total; seq++) {
            int start = seq * MAX_FRAGMENT_DATA;
            int end = Math.min(start + MAX_FRAGMENT_DATA, xml.length());
            String chunk = xml.substring(start, end);

            // Compute checksum
            String checksum = FragmentAssembler.computeChecksum(chunk);

            // Build fragment: SDR_COTF|msgId|seq|total|checksum|data
            String fragment = PREFIX_FRAG + msgId + "|" + seq + "|" + total + "|" +
                    checksum + "|" + chunk;

            fragments.add(fragment.getBytes(StandardCharsets.UTF_8));

            Log.d(TAG, "createFragments: [" + (seq + 1) + "/" + total + "] " +
                    fragment.length() + " bytes");
        }

        return fragments;
    }

    // =========================================================================
    // Decoding - wire format to XML
    // =========================================================================

    /**
     * Decode received payload to XML string.
     *
     * @param payload Received bytes
     * @return Complete XML string, or null if fragment not yet complete
     */
    public String decode(byte[] payload) {
        String raw = new String(payload, StandardCharsets.UTF_8);

        if (raw.startsWith(PREFIX_FRAG)) {
            return decodeFragment(raw);
        } else if (raw.startsWith(PREFIX_FULL)) {
            return decodeFull(raw);
        } else {
            Log.e(TAG, "decode: unknown prefix: " +
                    raw.substring(0, Math.min(20, raw.length())));
            return null;
        }
    }

    /**
     * Decode complete (non-fragmented) message.
     */
    private String decodeFull(String raw) {
        String xml = raw.substring(PREFIX_FULL.length());
        Log.d(TAG, "decodeFull: " + xml.length() + " bytes XML");
        return xml;
    }

    /**
     * Decode fragment and attempt reassembly.
     */
    private String decodeFragment(String raw) {
        try {
            // SDR_COTF|msgId|seq|total|checksum|data
            String data = raw.substring(PREFIX_FRAG.length());
            String[] parts = data.split("\\|", 5);

            if (parts.length < 5) {
                Log.e(TAG, "decodeFragment: expected 5 parts, got " + parts.length);
                return null;
            }

            String msgId = parts[0];
            int seq = Integer.parseInt(parts[1]);
            int total = Integer.parseInt(parts[2]);
            String checksum = parts[3];
            String chunk = parts[4];

            Log.d(TAG, "decodeFragment: msgId=" + msgId + " [" + (seq + 1) + "/" + total + "]");

            // Add to assembler
            String assembled = assembler.addFragment(msgId, seq, total, checksum, chunk);

            if (assembled != null) {
                Log.d(TAG, "decodeFragment: reassembly complete, " + assembled.length() + " bytes");
            }

            return assembled;

        } catch (Exception ex) {
            Log.e(TAG, "decodeFragment failed", ex);
            return null;
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    /**
     * Minify XML by removing unnecessary whitespace.
     */
    private String minifyXml(String xml) {
        if (xml == null) return "";
        // Remove XML declaration if present
        String result = xml.replaceAll("<\\?xml[^?]*\\?>", "");
        // Remove newlines and excessive spaces
        result = result.replaceAll(">\\s+<", "><");
        result = result.trim();
        return result;
    }
}
