package com.map.mbtiles.service;

/**
 * Immutable wrapper for a cached tile.
 * Stores the raw bytes alongside pre-computed metadata so the controller
 * never has to re-hash or re-inspect magic bytes on cache hits.
 */
public record TileEntry(
        byte[] data,
        String etag,
        boolean gzipped
) {}
