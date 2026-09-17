package com.map.mbtiles.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MBTiles 瓦片服务核心配置属性类（兼容 Java 8）
 * 映射 application.yml 中 prefix 为 "mbtiles" 的配置块
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mbtiles")
public class MbtilesProperties {

    /**
     * MBTiles / SQLite 数据文件存放目录（支持相对路径或绝对路径）
     */
    private String dataDir = "./data";

    /**
     * SQLite JDBC HikariCP 连接池配置
     */
    private PoolProperties pool = new PoolProperties();

    /**
     * 应用启动时的瓦片缓存预热配置
     */
    private WarmupProperties warmup = new WarmupProperties();

    /**
     * HTTP Cache-Control 缓存响应头配置
     */
    private CacheControlProperties cacheControl = new CacheControlProperties();

    /**
     * SQLite 瓦片表结构与字段自适应探测配置（支持扩展非标 SQLite / grids 表）
     */
    private SchemaProperties schema = new SchemaProperties();

    /**
     * 连接池详细参数配置（针对多文件海量数据源场景深度调优）
     */
    @Data
    public static class PoolProperties {
        /** 单数据集最大连接数（SQLite 单文件引擎推荐 10~20） */
        private int maxSize = 15;
        /** 最小空闲连接数（设为 0 可在无请求时自动缩容归零，释放文件句柄） */
        private int minIdle = 0;
        /** 空闲连接超时时间（毫秒，默认 60 秒无访问自动回收连接） */
        private long idleTimeout = 60000;
        /** 获取连接超时时间（毫秒） */
        private long connectionTimeout = 30000;
        /** 连接最大生存周期（毫秒，默认 30 分钟） */
        private long maxLifetime = 1800000;
        /** 允许同时常驻的最大活跃数据源连接池数量（超过时 LRU 淘汰最久未访问连接池） */
        private int maxActivePools = 50;
    }

    /**
     * 启动预热详细参数配置（防止多文件全量预热踩踏挤兑缓存）
     */
    @Data
    public static class WarmupProperties {
        /** 是否开启启动自动预热 */
        private boolean enabled = true;
        /** 预热最大缩放层级（0~maxZoom，采用单 SQL 批量流式预热） */
        private int maxZoom = 6;
        /** 默认最大预热的数据集数量（多文件时仅预热前 N 个，防止撑满 Caffeine） */
        private int maxDatasets = 3;
        /** 指定预热白名单数据集名称列表（配置后仅预热列表中指定的数据集，留空则按 maxDatasets 执行） */
        private List<String> includeDatasets = new ArrayList<>();
    }

    /**
     * 浏览器与 CDN 缓存响应头参数
     */
    @Data
    public static class CacheControlProperties {
        /** 缓存有效时长（秒，默认 7 天：604800 秒） */
        private long maxAge = 604800;
        /** 是否标记为 immutable（静态瓦片永不变更） */
        private boolean immutable = true;

        /**
         * 拼接为标准的 Cache-Control 响应头字符串
         */
        public String toHeaderValue() {
            String value = "public, max-age=" + maxAge + ", s-maxage=" + maxAge;
            if (immutable) {
                value += ", immutable";
            }
            return value;
        }
    }

    /**
     * SQLite 瓦片表与字段自适应探测配置
     */
    @Data
    public static class SchemaProperties {
        /** 候选表名列表（按优先级尝试，发现存在即采用） */
        private List<String> candidateTableNames = new ArrayList<>(Arrays.asList("tiles", "grids", "grid"));
        /** 候选瓦片二进制流字段名列表 */
        private List<String> candidateDataColumns = new ArrayList<>(Arrays.asList("tile_data", "grid", "data", "image", "content"));
        /** 候选缩放层级字段名列表 */
        private List<String> candidateZoomColumns = new ArrayList<>(Arrays.asList("zoom_level", "zoom", "z", "level"));
        /** 候选列号 (X) 字段名列表 */
        private List<String> candidateColumnColumns = new ArrayList<>(Arrays.asList("tile_column", "col", "column", "x"));
        /** 候选行号 (Y) 字段名列表 */
        private List<String> candidateRowColumns = new ArrayList<>(Arrays.asList("tile_row", "row", "y"));
        /** 特殊数据集个性化定制覆盖映射: datasetName -> CustomDatasetSchema */
        private Map<String, CustomDatasetSchema> datasetOverrides = new HashMap<>();
    }

    /**
     * 单数据集专属定制表结构覆盖配置
     */
    @Data
    public static class CustomDatasetSchema {
        private String tableName;
        private String dataColumn;
        private String zoomColumn;
        private String colColumn;
        private String rowColumn;
    }
}
