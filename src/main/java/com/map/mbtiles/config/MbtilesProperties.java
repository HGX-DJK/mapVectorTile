package com.map.mbtiles.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * MBTiles 瓦片服务核心配置属性类
 * 映射 application.yml 中 prefix 为 "mbtiles" 的配置块
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "mbtiles")
public class MbtilesProperties {

    /**
     * MBTiles 数据文件存放目录（支持相对路径或绝对路径）
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
     * 连接池详细参数配置
     */
    @Data
    public static class PoolProperties {
        /** 最大连接数（SQLite 单文件引擎推荐 10~20） */
        private int maxSize = 20;
        /** 最小空闲连接数 */
        private int minIdle = 5;
        /** 获取连接超时时间（毫秒） */
        private long connectionTimeout = 30000;
        /** 连接最大生存周期（毫秒，默认 30 分钟） */
        private long maxLifetime = 1800000;
    }

    /**
     * 启动预热详细参数配置
     */
    @Data
    public static class WarmupProperties {
        /** 是否开启启动自动预热 */
        private boolean enabled = true;
        /** 预热最大缩放层级（0~maxZoom，采用单 SQL 批量流式预热） */
        private int maxZoom = 6;
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
}
