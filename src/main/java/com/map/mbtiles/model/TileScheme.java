package com.map.mbtiles.model;

/**
 * 瓦片存储坐标系规范枚举
 */
public enum TileScheme {
    /**
     * TMS 规范：原点在左下角（南半球小、北半球大）
     * MBTiles 1.0 ~ 1.3 官方标准定义
     * 转换公式: tile_row = (1 << z) - 1 - y
     */
    TMS("tms"),

    /**
     * XYZ 规范：原点在左上角（北半球小、南半球大）
     * Web 地图标准（OpenStreetMap, Google Maps, Mapbox 请求标准）
     * 部分非标切片工具或自制 SQLite 直接按此格式物理存储: tile_row = y
     */
    XYZ("xyz");

    private final String value;

    TileScheme(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
     * 根据字符串解析 TileScheme，默认回退为 null
     */
    public static TileScheme fromString(String str) {
        if (str == null || str.isBlank()) {
            return null;
        }
        String clean = str.trim().toLowerCase();
        if ("xyz".equals(clean)) {
            return XYZ;
        }
        if ("tms".equals(clean)) {
            return TMS;
        }
        return null;
    }

    /**
     * 将标准 Web XYZ 坐标的 y 转换为对应数据库存储格式的 tile_row 行号
     *
     * @param z 缩放层级
     * @param y 标准 Web XYZ 瓦片行号 (左上角原点)
     * @return 数据库存储对应的物理 tile_row
     */
    public int toDatabaseRow(int z, int y) {
        return this == TMS ? ((1 << z) - 1 - y) : y;
    }

    /**
     * 将数据库中存储的物理 tile_row 还原为标准 Web XYZ 坐标的 y
     *
     * @param z       缩放层级
     * @param tileRow 数据库中的物理 tile_row
     * @return 标准 Web XYZ 瓦片行号 (左上角原点)
     */
    public int toWebY(int z, int tileRow) {
        return this == TMS ? ((1 << z) - 1 - tileRow) : tileRow;
    }
}
