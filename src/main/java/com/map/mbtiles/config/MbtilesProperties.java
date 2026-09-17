package com.map.mbtiles.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "mbtiles")
public class MbtilesProperties {

    /**
     * Directory path where the .mbtiles files are stored (relative or absolute).
     */
    private String dataDir = "./data";

    /**
     * SQLite JDBC connection pool settings (HikariCP).
     */
    private PoolProperties pool = new PoolProperties();

    /**
     * Tile cache warmup settings on application startup.
     */
    private WarmupProperties warmup = new WarmupProperties();

    /**
     * HTTP Cache-Control header settings.
     */
    private CacheControlProperties cacheControl = new CacheControlProperties();

    @Data
    public static class PoolProperties {
        private int maxSize = 20;
        private int minIdle = 5;
        private long connectionTimeout = 30000;
        private long maxLifetime = 1800000;
    }

    @Data
    public static class WarmupProperties {
        private boolean enabled = true;
        private int maxZoom = 6;
    }

    @Data
    public static class CacheControlProperties {
        private long maxAge = 604800; // 7 days in seconds
        private boolean immutable = true;

        public String toHeaderValue() {
            String value = "public, max-age=" + maxAge + ", s-maxage=" + maxAge;
            if (immutable) {
                value += ", immutable";
            }
            return value;
        }
    }
}
