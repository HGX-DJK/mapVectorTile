package com.map.mbtiles.model;

/**
 * 数据集底层 SQLite 瓦片表结构元信息
 * 支持标准 MBTiles (tiles 表 / tile_data 字段) 与各类非标网格库 (grids 表 / grid 字段等) 的自适应适配
 */
public record TableSchema(
        String tableName,
        String zoomCol,
        String colCol,
        String rowCol,
        String dataCol
) {
    /** 官方 MBTiles 标准表结构缺省单例 */
    public static final TableSchema DEFAULT = new TableSchema("tiles", "zoom_level", "tile_column", "tile_row", "tile_data");

    /**
     * 生成单点瓦片二进制数据查询 SQL
     */
    public String selectTileSql() {
        return "SELECT " + dataCol + " FROM " + tableName + " WHERE " + zoomCol + " = ? AND " + colCol + " = ? AND " + rowCol + " = ?";
    }

    /**
     * 生成启动批量瓦片预加载 SQL
     */
    public String selectWarmupSql() {
        return "SELECT " + zoomCol + ", " + colCol + ", " + rowCol + ", " + dataCol + " FROM " + tableName + " WHERE " + zoomCol + " <= ?";
    }

    /**
     * 生成实体表三元组联合索引创建 SQL
     */
    public String createIndexSql() {
        return "CREATE UNIQUE INDEX IF NOT EXISTS " + tableName + "_zxy_idx ON " + tableName + " (" + zoomCol + ", " + colCol + ", " + rowCol + ")";
    }
}
