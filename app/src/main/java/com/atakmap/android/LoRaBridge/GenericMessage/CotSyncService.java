package com.atakmap.android.LoRaBridge.GenericMessage;

import android.content.Context;

import com.atakmap.android.LoRaBridge.phy.UdpManager;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * CotSyncService
 *
 * Bidirectional synchronization pipeline for CoT messages (except chat)
 * between ATAK core and the SDR physical layer.
 *
 * Simplified flow:
 *
 *   ATAK → PHY:
 *     CotEvent.toString() → XML → SdrCotConverter.encode() → fragments → UDP → SDR
 *
 *   PHY → ATAK:
 *     SDR → UDP → SdrCotConverter.decode() → XML → CotEvent.parse() → dispatch
 *
 * No local database storage. Messages are transmitted and displayed directly.
 */
public class CotSyncService {

    private static final String TAG = "CotSyncService";
    private static CotSyncService instance;

    /** Converter for encoding/decoding XML with fragmentation support */
    private final SdrCotConverter converter;

    /** UDP transport for PHY communication */
    private final UdpManager udp = UdpManager.getInstance();

    /** Tracks processed message UIDs to prevent loops */
    private final Set<String> processedUids = new HashSet<>();
    private static final int MAX_TRACKED = 2000;

    /** Delay between fragments in milliseconds */
    private static final int FRAGMENT_DELAY_MS = 100;

    /**
     * Private constructor.
     */
    private CotSyncService(Context ctx) {
        this.converter = new SdrCotConverter();
        udp.setCotHandler(this::handlePhyPayload);
        Log.d(TAG, "CotSyncService initialized");
    }

    /**
     * Global singleton access.
     */
    public static synchronized CotSyncService getInstance(Context ctx) {
        if (instance == null) {
            instance = new CotSyncService(ctx);
        }
        return instance;
    }

    // =========================================================================
    // ATAK → PHY Path
    // =========================================================================

    /**
     * Entry point for ATAK-origin CoT events (except chat).
     *
     * Pipeline:
     *   - Skip chat (b-t-f) messages
     *   - Skip events already tagged with __plugin
     *   - Encode XML with automatic fragmentation
     *   - Send fragments over SDR
     */
    public void processIncomingCotFromAtak(CotEvent event) {
        if (event == null || !event.isValid()) return;

        String type = event.getType();
        String uid = event.getUID();

        // Chat messages handled by ChatSyncService
        if (type != null && type.startsWith("b-t-f")) return;

        // Loop prevention: skip PHY-origin events
        if (hasLoopTag(event)) {
            Log.d(TAG, "Skip looped CoT uid=" + uid);
            return;
        }

        // Deduplication by UID + time
        String dedupKey = uid + "|" + event.getTime().getMilliseconds();
        if (isProcessed(dedupKey)) {
            Log.d(TAG, "Skip duplicate CoT uid=" + uid);
            return;
        }
        markProcessed(dedupKey);

        // Get complete XML
        String xml = event.toString();
        if (xml == null || xml.isEmpty()) {
            Log.w(TAG, "Empty XML for uid=" + uid);
            return;
        }

        Log.d(TAG, "Processing CoT: uid=" + uid + ", type=" + type + ", xml=" + xml.length() + " bytes");

        // Encode and send (with fragmentation if needed)
        sendToSdr(xml, uid);
    }

    /**
     * Encode XML and send to SDR with automatic fragmentation.
     */
    private void sendToSdr(String xml, String uid) {
        try {
            List<byte[]> fragments = converter.encode(xml);

            if (fragments.isEmpty()) {
                Log.w(TAG, "encode() returned empty list for uid=" + uid);
                return;
            }

            for (int i = 0; i < fragments.size(); i++) {
                byte[] frag = fragments.get(i);
                udp.sendCot(frag);

                Log.d(TAG, "Sent fragment [" + (i + 1) + "/" + fragments.size() + "] " +
                        frag.length + " bytes");

                // Delay between fragments to avoid congestion
                if (fragments.size() > 1 && i < fragments.size() - 1) {
                    try {
                        Thread.sleep(FRAGMENT_DELAY_MS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            Log.d(TAG, "Sent CoT uid=" + uid + " in " + fragments.size() + " fragment(s)");

        } catch (Exception ex) {
            Log.e(TAG, "sendToSdr failed for uid=" + uid, ex);
        }
    }

    // =========================================================================
    // PHY → ATAK Path
    // =========================================================================

    /**
     * Handle raw payload from SDR.
     *
     * Decodes bytes into XML (handling fragment reassembly),
     * parses to CotEvent, tags it, and dispatches to ATAK.
     */
    private void handlePhyPayload(byte[] payload) {
        try {
            // Decode bytes → XML (returns null if waiting for more fragments)
            String xml = converter.decode(payload);

            if (xml == null || xml.isEmpty()) {
                // Either decode failed or waiting for more fragments
                return;
            }

            Log.d(TAG, "Received complete XML: " + xml.length() + " bytes");

            // Parse XML → CotEvent
            CotEvent event = CotEvent.parse(xml);
            if (event == null || !event.isValid()) {
                Log.e(TAG, "Failed to parse XML to CotEvent");
                return;
            }

            String uid = event.getUID();
            String type = event.getType();

            // Skip chat (should be handled by ChatSyncService)
            if (type != null && type.startsWith("b-t-f")) {
                Log.d(TAG, "Skip chat CoT from PHY");
                return;
            }

            // Deduplication
            String dedupKey = uid + "|" + event.getTime().getMilliseconds();
            if (isProcessed(dedupKey)) {
                Log.d(TAG, "Skip duplicate PHY CoT uid=" + uid);
                return;
            }
            markProcessed(dedupKey);

            // Add loop tag to prevent PHY → ATAK → PHY cycling
            addLoopTag(event, "PHY");

            // Dispatch to ATAK
            CotMapComponent.getInternalDispatcher().dispatch(event);

            Log.d(TAG, "Dispatched PHY CoT to ATAK: uid=" + uid + ", type=" + type);

        } catch (Throwable t) {
            Log.e(TAG, "handlePhyPayload error", t);
        }
    }

    // =========================================================================
    // Loop Prevention
    // =========================================================================

    /** Check if event already contains a __plugin loop-prevention tag */
    private static boolean hasLoopTag(CotEvent ev) {
        CotDetail d = ev.getDetail();
        return d != null && d.getFirstChildByName(0, "__plugin") != null;
    }

    /** Attach __plugin tag to mark origin */
    private static void addLoopTag(CotEvent ev, String origin) {
        CotDetail d = ev.getDetail();
        if (d == null) {
            d = new CotDetail("detail");
            ev.setDetail(d);
        }
        CotDetail tag = new CotDetail("__plugin");
        tag.setAttribute("origin", origin);
        d.addChild(tag);
    }

    // =========================================================================
    // Deduplication
    // =========================================================================

    private synchronized boolean isProcessed(String key) {
        return processedUids.contains(key);
    }

    private synchronized void markProcessed(String key) {
        if (processedUids.size() > MAX_TRACKED) {
            processedUids.clear();
        }
        processedUids.add(key);
    }
}
