package com.map.mbtiles.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * MBTiles 丰富元数据模型（兼容 Java 8）
 * 解析并封装 GIS 行业标准元数据属性（完全兼容 TileJSON 3.0 与 MBTiles 1.3 规范），
 * 并提供缩放层级（Zoom）与地理空间外包矩形（BBox）的前置短路剪枝能力。
 */
@Data
@Builder
public class DatasetInfo {

    private static final Logger log = LoggerFactory.getLogger(DatasetInfo.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 数据集路由唯一标识（即文件路径去除后缀，如 basemap_line_point 或 vector/roads） */
    private String id;
    /** 数据集名称（通常为文件名去掉 .mbtiles 或 metadata 中的 name 声明） */
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
    /** 各缩放层级（0~22）预计算的瓦片边界盒数组（为 null 表示全球全量覆盖），实现 O(1) 拓扑短路剪枝 */
    private TileRange[] tileRanges;
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
     * 瓦片行列号边界范围紧凑结构体（启动/加载时预计算完成，杜绝运行时的浮点与三角函数开销，兼容 Java 8）
     */
    public static final class TileRange {
        private final int minX;
        private final int maxX;
        private final int minY;
        private final int maxY;

        public TileRange(int minX, int maxX, int minY, int maxY) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
        }

        public int minX() {
            return minX;
        }

        public int maxX() {
            return maxX;
        }

        public int minY() {
            return minY;
        }

        public int maxY() {
            return maxY;
        }

        public int getMinX() {
            return minX;
        }

        public int getMaxX() {
            return maxX;
        }

        public int getMinY() {
            return minY;
        }

        public int getMaxY() {
            return maxY;
        }

        public boolean contains(int x, int y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TileRange tileRange = (TileRange) o;
            return minX == tileRange.minX && maxX == tileRange.maxX && minY == tileRange.minY && maxY == tileRange.maxY;
        }

        @Override
        public int hashCode() {
            return Objects.hash(minX, maxX, minY, maxY);
        }

        @Override
        public String toString() {
            return "TileRange{" +
                    "minX=" + minX +
                    ", maxX=" + maxX +
                    ", minY=" + minY +
                    ", maxY=" + maxY +
                    '}';
        }
    }

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
     * 地理空间范围（BBox）拓扑短路校验（O(1) 预计算查表）：
     * 直接读取启动/加载时预计算完成的瓦片边界盒，
     * 若请求的 (x, y) 在该层级瓦片外包范围之外，直接短路阻断，零数据库与缓存开销。
     *
     * @param z 缩放层级
     * @param x 瓦片列号
     * @param y 瓦片行号 (XYZ)
     * @return 瓦片是否在数据集地理范围（附带 1 个瓦片缓冲）内
     */
    public boolean isTileWithinBounds(int z, int x, int y) {
        if (tileRanges == null) {
            return true;
        }
        if (z < 0) {
            return false;
        }
        if (z >= tileRanges.length) {
            // 超出预计算范围时，保守放行，由后续 SQLite 真实检索兜底，防止误杀高层级瓦片
            return true;
        }
        TileRange range = tileRanges[z];
        return range == null || range.contains(x, y);
    }

    /**
     * 预计算默认 0~22 各层级的瓦片边界范围盒（墨卡托投影三角函数与对数只计算一次）
     */
    public static TileRange[] computeTileRanges(double[] bounds) {
        return computeTileRanges(bounds, 22);
    }

    /**
     * 预计算指定最高层级的瓦片边界范围盒（支持高层级动态扩展）
     */
    public static TileRange[] computeTileRanges(double[] bounds, int maxZoom) {
        if (bounds == null || bounds.length < 4) {
            return null;
        }

        // bounds 顺序：[西经(minLng), 南纬(minLat), 东经(maxLng), 北纬(maxLat)]
        double minLng = bounds[0];
        double minLat = bounds[1];
        double maxLng = bounds[2];
        double maxLat = bounds[3];

        // 若覆盖全球范围或默认值，返回 null 表示无需剪枝
        if (minLng <= -180.0 && maxLng >= 180.0 && minLat <= -85.0 && maxLat >= 85.0) {
            return null;
        }

        double clampedMaxLat = Math.min(85.05112878, Math.max(-85.05112878, maxLat));
        double clampedMinLat = Math.min(85.05112878, Math.max(-85.05112878, minLat));

        double maxLatRad = Math.toRadians(clampedMaxLat);
        double minLatRad = Math.toRadians(clampedMinLat);

        // 墨卡托投影 Y 轴归一化比例因子（0.0 ~ 1.0），超越函数全局仅计算一次
        double yFactor1 = (1.0 - Math.log(Math.tan(maxLatRad) + 1.0 / Math.cos(maxLatRad)) / Math.PI) / 2.0;
        double yFactor2 = (1.0 - Math.log(Math.tan(minLatRad) + 1.0 / Math.cos(minLatRad)) / Math.PI) / 2.0;

        double xFactor1 = (minLng + 180.0) / 360.0;
        double xFactor2 = (maxLng + 180.0) / 360.0;

        int targetMax = Math.min(30, Math.max(22, maxZoom));
        TileRange[] ranges = new TileRange[targetMax + 1];
        for (int z = 0; z <= targetMax; z++) {
            int maxTiles = 1 << z;

            int minTileX = Math.max(0, (int) Math.floor(xFactor1 * maxTiles) - 1);
            int maxTileX = Math.min(maxTiles - 1, (int) Math.floor(xFactor2 * maxTiles) + 1);

            int minTileY = Math.max(0, (int) Math.floor(yFactor1 * maxTiles) - 1);
            int maxTileY = Math.min(maxTiles - 1, (int) Math.floor(yFactor2 * maxTiles) + 1);

            ranges[z] = new TileRange(minTileX, maxTileX, minTileY, maxTileY);
        }
        return ranges;
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
        TileRange[] tileRanges = computeTileRanges(bounds);

        List<Map<String, Object>> vectorLayers = Collections.emptyList();
        String jsonField = meta.get("json");
        if (jsonField != null && !jsonField.trim().isEmpty()) {
            try {
                Map<String, Object> parsedJson = MAPPER.readValue(jsonField, new TypeReference<Map<String, Object>>() {});
                Object layersObj = parsedJson.get("vector_layers");
                if (layersObj instanceof List) {
                    List<?> list = (List<?>) layersObj;
                    vectorLayers = new ArrayList<>();
                    for (Object item : list) {
                        if (item instanceof Map) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> casted = (Map<String, Object>) item;
                            vectorLayers.add(casted);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("解析数据集 {} 的 json 扩展字段失败: {}", datasetName, e.getMessage());
            }
        }

        return DatasetInfo.builder()
                .id(datasetName)
                .name(meta.getOrDefault("name", datasetName))
                .description(meta.getOrDefault("description", ""))
                .format(meta.getOrDefault("format", "pbf"))
                .version(meta.getOrDefault("version", "1.0.0"))
                .attribution(meta.getOrDefault("attribution", ""))
                .type(meta.getOrDefault("type", "baselayer"))
                .minzoom(minzoom != null ? minzoom : 0)
                .maxzoom(maxzoom != null ? maxzoom : 22)
                .bounds(bounds != null ? bounds : new double[]{-180.0, -85.05112878, 180.0, 85.05112878})
                .tileRanges(tileRanges)
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
        if (val == null || val.trim().isEmpty()) {
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
        if (val == null || val.trim().isEmpty()) {
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
