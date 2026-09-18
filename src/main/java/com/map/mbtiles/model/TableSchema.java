package com.map.mbtiles.model;

import java.util.Objects;

/**
 * 数据集底层 SQLite 瓦片表结构元信息（兼容 Java 8）
 * 支持标准 MBTiles (tiles 表 / tile_data 字段) 与各类非标网格库 (grids 表 / grid 字段等) 的自适应适配
 */
public final class TableSchema {

    private final String tableName;
    private final String zoomCol;
    private final String colCol;
    private final String rowCol;
    private final String dataCol;

    /** 官方 MBTiles 标准表结构缺省单例 */
    public static final TableSchema DEFAULT = new TableSchema("tiles", "zoom_level", "tile_column", "tile_row", "tile_data");

    public TableSchema(String tableName, String zoomCol, String colCol, String rowCol, String dataCol) {
        this.tableName = tableName;
        this.zoomCol = zoomCol;
        this.colCol = colCol;
        this.rowCol = rowCol;
        this.dataCol = dataCol;
    }

    public String tableName() {
        return this.tableName;
    }

    public String zoomCol() {
        return this.zoomCol;
    }

    public String colCol() {
        return this.colCol;
    }

    public String rowCol() {
        return this.rowCol;
    }

    public String dataCol() {
        return this.dataCol;
    }

    public String getTableName() {
        return this.tableName;
    }

    public String getZoomCol() {
        return this.zoomCol;
    }

    public String getColCol() {
        return this.colCol;
    }

    public String getRowCol() {
        return this.rowCol;
    }

    public String getDataCol() {
        return this.dataCol;
    }

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
     * 生成层级区间批量瓦片预加载 SQL（支持城市级/高层级切片自适应预热）
     */
    public String selectWarmupRangeSql() {
        return "SELECT " + zoomCol + ", " + colCol + ", " + rowCol + ", " + dataCol + " FROM " + tableName + " WHERE " + zoomCol + " >= ? AND " + zoomCol + " <= ?";
    }

    /**
     * 生成实体表三元组联合索引创建 SQL
     */
    public String createIndexSql() {
        return "CREATE UNIQUE INDEX IF NOT EXISTS " + tableName + "_zxy_idx ON " + tableName + " (" + zoomCol + ", " + colCol + ", " + rowCol + ")";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TableSchema that = (TableSchema) o;
        return Objects.equals(tableName, that.tableName) &&
                Objects.equals(zoomCol, that.zoomCol) &&
                Objects.equals(colCol, that.colCol) &&
                Objects.equals(rowCol, that.rowCol) &&
                Objects.equals(dataCol, that.dataCol);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName, zoomCol, colCol, rowCol, dataCol);
    }

    @Override
    public String toString() {
        return "TableSchema{" +
                "tableName='" + tableName + '\'' +
                ", zoomCol='" + zoomCol + '\'' +
                ", colCol='" + colCol + '\'' +
                ", rowCol='" + rowCol + '\'' +
                ", dataCol='" + dataCol + '\'' +
                '}';
    }
}
