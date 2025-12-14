package com.atakmap.android.LoRaBridge.phy;

import com.atakmap.android.LoRaBridge.Database.GenericCotEntity;

/**
 * GenericCotConverter
 *
 * Interface for encoding/decoding CoT entities to/from wire format.
 *
 * Implementations handle the specific encoding scheme (e.g., EXI, compact text, etc.)
 * and may support fragmentation for large messages.
 */
public interface GenericCotConverter {

    /**
     * Decode raw bytes from PHY layer into a GenericCotEntity.
     *
     * For fragmented messages, this may return null until all fragments
     * are received and reassembled.
     *
     * @param payload Raw bytes received from SDR
     * @return Decoded entity, or null if decode failed or awaiting fragments
     */
    GenericCotEntity decode(byte[] payload);

    /**
     * Encode a GenericCotEntity into bytes for transmission.
     *
     * Note: For messages that may need fragmentation, use
     * SdrCotConverter.encodeWithFragments() instead.
     *
     * @param entity Entity to encode
     * @return Encoded bytes, or null if encoding failed or message too large
     */
    byte[] encode(GenericCotEntity entity);
}