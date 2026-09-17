package com.map.mbtiles.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MBTiles 丰富元数据模型
 * 解析并封装 GIS 行业标准元数据属性（完全兼容 TileJSON 3.0 与 MBTiles 1.3 规范）
 */
@Slf4j
@Data
@Builder
public class DatasetInfo {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 数据集名称（通常为文件名去掉 .mbtiles） */
    private String name;
    /** 数据集描述信息 */
    private String description;
    /** 瓦片格式（如 pbf, mvt） */
    private String format;
    /** 数据集版本号（如 1.0.0） */
    private String version;
    /** 数据归属/版权声明 */
    private String attribution;
    /** 图层类型：baselayer（基础底图）或 overlay（叠加图层） */
    private String type;
    /** 最小缩放层级（minzoom） */
    private Integer minzoom;
    /** 最大缩放层级（maxzoom） */
    private Integer maxzoom;
    /** 空间边界范围 [西经, 南纬, 东经, 北纬] */
    private double[] bounds;
    /** 地图默认中心点坐标 [经度, 纬度, 缩放层级] */
    private double[] center;
    /** 矢量图层定义列表（包含图层 id 及字段属性） */
    private List<Map<String, Object>> vectorLayers;
    /** MBTiles 文件大小（字节） */
    private long fileSize;
    /** 文件最后修改时间戳（毫秒） */
    private long lastModified;
    /** SQLite 中原始的 metadata 键值对集合 */
    private Map<String, String> rawMetadata;

    /**
     * 快速校验请求的缩放层级（z）是否处于该数据集的有效范围内
     *
     * @param z 请求的缩放级别
     * @return 如果在 [minzoom, maxzoom] 之间返回 true；否则返回 false
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
     * 将 SQLite metadata 表中的键值对解析为结构化的 DatasetInfo 实体
     *
     * @param datasetName  数据集名称
     * @param meta         SQLite 查询出的键值对映射
     * @param fileSize     文件大小
     * @param lastModified 最后修改时间
     * @return 解析完成的 DatasetInfo 对象
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
                log.debug("解析数据集 {} 的 json 扩展字段失败: {}", datasetName, e.getMessage());
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

    /**
     * 辅助方法：将字符串安全解析为整数
     */
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

    /**
     * 辅助方法：将逗号分隔的字符串解析为 double 数组
     */
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
