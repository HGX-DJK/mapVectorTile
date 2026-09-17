package com.map.mbtiles.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Rich model representing MBTiles metadata with parsed GIS standard properties
 * (TileJSON 3.0 / MBTiles spec 1.3 compatible).
 */
@Slf4j
@Data
@Builder
public class DatasetInfo {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String name;
    private String description;
    private String format;
    private String version;
    private String attribution;
    private String type; // baselayer / overlay
    private Integer minzoom;
    private Integer maxzoom;
    private double[] bounds; // [w, s, e, n]
    private double[] center; // [lng, lat, zoom]
    private List<Map<String, Object>> vectorLayers;
    private long fileSize;
    private long lastModified;
    private Map<String, String> rawMetadata;

    /**
     * Quickly checks if a requested zoom level is valid for this dataset.
     */
    public boolean isZoomValid(int z) {
        if (minzoom != null && z < minzoom) {
            return false;
        }
        if (maxzoom != null && z > maxzoom) {
            return false;
        }
        return true;
    }

    /**
     * Parses raw SQLite metadata key-value pairs into structured DatasetInfo.
     */
    public static DatasetInfo fromMetadata(String datasetName,
                                          Map<String, String> meta,
                                          long fileSize,
                                          long lastModified) {
        Integer minzoom = parseInteger(meta.get("minzoom"));
        Integer maxzoom = parseInteger(meta.get("maxzoom"));
        double[] bounds = parseDoubleArray(meta.get("bounds"));
        double[] center = parseDoubleArray(meta.get("center"));

        List<Map<String, Object>> vectorLayers = Collections.emptyList();
        String jsonField = meta.get("json");
        if (jsonField != null && !jsonField.isBlank()) {
            try {
                Map<String, Object> parsedJson = MAPPER.readValue(jsonField, new TypeReference<>() {});
                Object layersObj = parsedJson.get("vector_layers");
                if (layersObj instanceof List<?> list) {
                    vectorLayers = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> casted = (Map<String, Object>) map;
                            vectorLayers.add(casted);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Could not parse 'json' field for dataset {}: {}", datasetName, e.getMessage());
            }
        }

        return DatasetInfo.builder()
                .name(meta.getOrDefault("name", datasetName))
                .description(meta.getOrDefault("description", ""))
                .format(meta.getOrDefault("format", "pbf"))
                .version(meta.getOrDefault("version", "1.0.0"))
                .attribution(meta.getOrDefault("attribution", ""))
                .type(meta.getOrDefault("type", "baselayer"))
                .minzoom(minzoom != null ? minzoom : 0)
                .maxzoom(maxzoom != null ? maxzoom : 22)
                .bounds(bounds != null ? bounds : new double[]{-180.0, -85.05112878, 180.0, 85.05112878})
                .center(center != null ? center : new double[]{0.0, 0.0, 2.0})
                .vectorLayers(vectorLayers)
                .fileSize(fileSize)
                .lastModified(lastModified)
                .rawMetadata(meta)
                .build();
    }

    private static Integer parseInteger(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double[] parseDoubleArray(String val) {
        if (val == null || val.isBlank()) {
            return null;
        }
        try {
            String[] parts = val.split(",");
            double[] res = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                res[i] = Double.parseDouble(parts[i].trim());
            }
            return res;
        } catch (Exception e) {
            return null;
        }
    }
}
