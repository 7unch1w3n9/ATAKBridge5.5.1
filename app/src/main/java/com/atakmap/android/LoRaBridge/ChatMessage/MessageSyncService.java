package com.atakmap.android.LoRaBridge.ChatMessage;

import android.content.Context;
import android.os.Bundle;

import com.atakmap.android.LoRaBridge.Database.ChatMessageEntity;
import com.atakmap.android.LoRaBridge.Database.ChatRepository;
import com.atakmap.android.LoRaBridge.phy.UdpManager;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * MessageSyncService
 *
 * Core synchronization component that bridges between:
 *   1) GeoChat / CoT events and the local plugin database
 *   2) Local plugin messages and GeoChat
 *   3) Local plugin messages and the LoRa flowgraph
 *
 * Responsibilities:
 *  - Parse incoming CoT events, convert to ChatMessageEntity, store in DB
 *  - Take locally created messages, convert to CoT, and send to GeoChat
 *  - Encode messages for LoRa with automatic fragmentation, send via UDP to the flowgraph
 *  - Decode messages from the flowgraph (including fragment reassembly) and reinject into GeoChat
 *
 * It also performs duplicate suppression so that messages are not processed
 * multiple times when they travel through different channels.
 */
public class MessageSyncService {

    private static final String TAG = "MessageSyncService";

    /** Singleton instance */
    private static MessageSyncService instance;

    /** Repository for persistent chat storage */
    private final ChatRepository chatRepository;

    /** Converts ChatMessageEntity <-> CoT event */
    private final IncomingPluginManager incomingPluginManager;

    /** Tracks plugin originated messages to avoid reprocessing */
    private final MessageTracker messageTracker = new MessageTracker();

    /** Tracks PHY originated messages to avoid duplicate handling */
    private final Set<String> processedMessageIds = new HashSet<>();

    /** Converter for encoding/decoding LoRa payloads with fragmentation support */
    private final ChatFragmentConverter fragmentConverter;

    /** Shared UDP manager used as physical transport to the flowgraph */
    private final UdpManager udp = UdpManager.getInstance();

    /** Maximum hop count for relay messages */
    private static final int MAX_HOP_COUNT = 3;

    /** Tracks relayed message IDs to prevent duplicate relays */
    private final Set<String> relayedMessageIds = new HashSet<>();

    /**
     * Private constructor. Use getInstance() to obtain the singleton.
     */
    private MessageSyncService(Context context) {
        this.chatRepository = new ChatRepository(context);
        this.incomingPluginManager = new IncomingPluginManager();
        this.fragmentConverter = new ChatFragmentConverter();

        // Register handler for incoming UDP chat payloads from the flowgraph
        // This handler processes both complete messages and fragments
        udp.setChatHandler(this::handleFlowgraphMessage);
    }

    /**
     * Singleton accessor.
     */
    public static synchronized MessageSyncService getInstance(Context context) {
        if (instance == null) {
            instance = new MessageSyncService(context.getApplicationContext());
        }
        return instance;
    }


    // =========================================================================
    // Incoming from GeoChat (CoT events) -> DB
    // =========================================================================

    /**
     * Entry point for CoT events that originate from GeoChat.
     *
     * Security and filtering:
     *  - Rejects invalid CoT events.
     *  - Skips messages that are already marked as plugin processed (__plugin).
     *  - Distinguishes between self messages from GeoChat and other sources.
     *
     * @param event  raw CoT event
     * @param meta array of recipient UIDs resolved by the caller
     */
    public void processIncomingCotEventFromGeoChat(CotEvent event, Bundle meta) {
        if (!isValidGeoChatEvent(event)) return;

        CotDetail lora = event.getDetail().getFirstChildByName(0, "__plugin");
        if (lora != null) {
            Log.d(TAG, "Skipping plugin processed message to avoid loop");
            return;
        }

        ChatMessageEntity entity = ChatMessageFactory.fromCotEvent(event, meta);
        if (entity == null) {
            Log.w(TAG, "Failed to create entity from CoT event");
            return;
        }

        if (isSelfMessage(entity)) {
            if ("GeoChat".equals(entity.getOrigin())) {
                Log.d(TAG, "Processing self-originated GeoChat message");
                saveMessageIfNew(entity);
            } else {
                Log.d(TAG, "Ignoring self-originated non-GeoChat message");
            }
        } else {
            saveMessageIfNew(entity);
        }
    }


    // =========================================================================
    // Outgoing from DB -> GeoChat (+ optional LoRa)
    // =========================================================================

    /**
     * Process a message that was generated locally and stored in the database.
     *
     * Direction:
     *   Plugin (DB) → GeoChat (CoT) and possibly LoRa flowgraph.
     *
     * This only handles messages whose origin is "Plugin".
     */
    public void processOutgoingMessage(ChatMessageEntity message) {
        if ("Plugin".equals(message.getOrigin())) {

            if (messageTracker.isProcessed(message.getId())) {
                Log.d(TAG, "Message already processed: " + message.getId());
                return;
            }

            messageTracker.markProcessed(message.getId());

            // Convert to CoT and forward to GeoChat
            CotEvent cotEvent = incomingPluginManager.convertChatMessageToCotEvent(message);
            if (cotEvent != null) {
                if (cotEvent.getDetail() != null) {
                    incomingPluginManager.sendToGeoChat(cotEvent);
                }
            }

            // Optionally send a copy over LoRa
            if (shouldSendToLoRa(message)) {
                sendToFlowgraph(message);
            }
        }
    }

    /**
     * Encode a message with automatic fragmentation and send to the LoRa flowgraph over UDP.
     *
     * This method handles both small messages (sent as single packet) and large messages
     * (automatically fragmented into multiple packets).
     */
    private void sendToFlowgraph(ChatMessageEntity message) {
        try {
            // Encode message with automatic fragmentation
            List<byte[]> fragments = fragmentConverter.encode(message);

            if (fragments.isEmpty()) {
                Log.w(TAG, "Failed to encode message: " + message.getId());
                return;
            }

            // Send all fragments
            for (int i = 0; i < fragments.size(); i++) {
                byte[] fragment = fragments.get(i);
                udp.sendChatRaw(fragment);

                Log.d(TAG, String.format("Sent fragment %d/%d for message %s (%d bytes)",
                        i + 1, fragments.size(), message.getId(), fragment.length));
            }

            Log.d(TAG, String.format("Successfully sent message %s in %d fragment(s)",
                    message.getId(), fragments.size()));

        } catch (Exception e) {
            Log.e(TAG, "Failed to send to Flowgraph", e);
        }
    }


    // =========================================================================
    // Incoming from PHY (LoRa / flowgraph) -> DB -> GeoChat
    // =========================================================================

    /**
     * Handler registered with UdpManager to process incoming UDP chat payloads
     * from the flowgraph. It decodes the payload (handling both complete messages
     * and fragments) into a ChatMessageEntity and forwards it to processIncomingPhyMessage.
     *
     * For fragmented messages, this may be called multiple times (once per fragment)
     * before a complete message is returned.
     */
    public void handleFlowgraphMessage(byte[] payload) {
        try {
            Log.d(TAG, "Received Flowgraph payload (" + payload.length + " bytes)");

            // Decode payload - returns null if waiting for more fragments
            ChatMessageEntity message = fragmentConverter.decode(payload);

            if (message == null) {
                Log.d(TAG, "Fragment received, waiting for complete message");
                return;
            }

            // Complete message received (either single packet or reassembled)
            processIncomingPhyMessage(message);

        } catch (Exception e) {
            Log.e(TAG, "Error handling Flowgraph message", e);
        }
    }

    /**
     * Check if a given message id has already been processed by the plugin.
     */
    public boolean isMessageProcessed(String messageId) {
        return messageTracker.isProcessed(messageId);
    }


    // =========================================================================
    // Validation and helpers
    // =========================================================================

    /** Validate GeoChat CoT event shape. */
    private boolean isValidGeoChatEvent(CotEvent event) {
        return event != null
                && event.isValid()
                && "b-t-f".equals(event.getType())
                && event.getDetail() != null;
    }

    /** Check if message was sent by this device (self message). */
    private boolean isSelfMessage(ChatMessageEntity entity) {
        return entity.getSenderUid() != null
                && entity.getSenderUid().equals(MapView.getDeviceUid());
    }

    /**
     * Insert a message only if it does not already exist in the database.
     */
    private void saveMessageIfNew(ChatMessageEntity entity) {
        boolean inserted = chatRepository.insertIfNotExists(entity);
        if (inserted) {
            Log.d(TAG, "New message saved: " + entity.getMessage());
        } else {
            Log.d(TAG, "Duplicate message ignored");
        }
    }


    // =========================================================================
    // Local message tracker (for Plugin origin)
    // =========================================================================

    /**
     * Message tracker to prevent duplicate processing of locally created
     * Plugin messages (for example when they are looped back).
     */
    private static class MessageTracker {
        private final Set<String> processedIds = new HashSet<>();

        /**
         * Check if a message id was already processed.
         * The internal set is periodically cleared to avoid unbounded growth.
         */
        public synchronized boolean isProcessed(String id) {
            return processedIds.contains(id);
        }

        public synchronized void markProcessed(String id) {
            if (processedIds.size() > 100) {
                processedIds.clear();
            }
            processedIds.add(id);
        }
    }

    /**
     * Determine whether a given message should also be forwarded to LoRa.
     * Currently always true. Can be refined to exclude broadcast rooms etc.
     */
    private boolean shouldSendToLoRa(ChatMessageEntity message) {
        Log.d(TAG, "Sending " + message.getId() + " to Flowgraph");
        return true;
    }


    // =========================================================================
    // Incoming PHY messages integration
    // =========================================================================

    /**
     * Process messages that originate from the physical layer (LoRa).
     *
     * Steps:
     *  - Deduplicate by message id
     *  - Check if message is for this device or needs relay
     *  - Tag origin as "PHY" for messages destined to this device
     *  - Rebuild raw CoT representation (placeholder)
     *  - Insert into database if not present
     *  - Convert to CoT and forward into GeoChat
     *  - Relay message if it's for another device and hop count allows
     */
    public void processIncomingPhyMessage(ChatMessageEntity message) {
        if (message == null) return;

        Log.d(TAG, "Receive " + message.getId() + " from Flowgraph");

        if (processedMessageIds.contains(message.getId())) {
            Log.d(TAG, "Duplicate PHY message ignored: " + message.getId());
            return;
        }

        processedMessageIds.add(message.getId());

        String myUid = MapView.getDeviceUid();
        String receiverUid = message.getReceiverUid();
        boolean isForMe = receiverUid != null && receiverUid.trim().equals(myUid.trim());
        boolean isBroadcast = "All Chat Rooms".equalsIgnoreCase(receiverUid);

        if (isForMe || isBroadcast) {
            handleMessageForMe(message);
        } else {
            handleRelayMessage(message);
        }
    }

    /**
     * Handle message destined for this device.
     * Store in database and forward to GeoChat.
     */
    private void handleMessageForMe(ChatMessageEntity message) {
        message.setOrigin("PHY");
        rebuildRawCot(message);

        boolean inserted = chatRepository.insertIfNotExists(message);
        if (inserted) {
            Log.d(TAG, "New PHY message saved: " + message.getMessage());
            CotEvent event = incomingPluginManager.convertChatMessageToCotEvent(message);
            if (event != null) {
                incomingPluginManager.sendToGeoChat(event);
            }
        } else {
            Log.d(TAG, "Duplicate PHY message ignored");
        }
    }

    /**
     * Handle message destined for another device (relay).
     * Check hop count and relay if allowed.
     */
    private void handleRelayMessage(ChatMessageEntity message) {
        if (relayedMessageIds.contains(message.getId())) {
            Log.d(TAG, "Already relayed, skip: " + message.getId());
            return;
        }

        if (message.getHopCount() >= MAX_HOP_COUNT) {
            Log.d(TAG, "Max hop count reached, drop: " + message.getId()
                    + " (hopCount=" + message.getHopCount() + ")");
            return;
        }

        // Increment hop count
        message.setHopCount(message.getHopCount() + 1);

        // Mark as relayed
        relayedMessageIds.add(message.getId());
        if (relayedMessageIds.size() > 500) {
            relayedMessageIds.clear();
        }

        Log.d(TAG, "Relaying message: " + message.getId()
                + " to " + message.getReceiverUid()
                + " (hopCount=" + message.getHopCount() + ")");

        // Rebroadcast with automatic fragmentation
        sendToFlowgraph(message);
    }

    /**
     * Rebuilds a placeholder raw CoT string if it is missing.
     * Currently this uses message.toString() as a stand in.
     */
    private void rebuildRawCot(ChatMessageEntity message) {
        if (message.getCotRawXml() != null && !message.getCotRawXml().isEmpty()) {
            return;
        }
        message.setCotRawXml(message.toString());
    }


    // =========================================================================
    // Shutdown
    // =========================================================================

    /**
     * Clear internal state and release resources.
     */
    public void shutdown() {
        processedMessageIds.clear();
        relayedMessageIds.clear();
        Log.d(TAG, "SyncService cleaned up");
    }
}