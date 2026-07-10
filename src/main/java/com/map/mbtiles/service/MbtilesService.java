package com.map.mbtiles.service;

import com.map.mbtiles.config.MbtilesProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.sqlite.SQLiteConfig;

import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class MbtilesService {

    private final MbtilesProperties properties;
    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();

    public MbtilesService(MbtilesProperties properties) {
        this.properties = properties;
    }

    /**
     * Gets (or lazily initialises) the connection pool for a given dataset.
     */
    private DataSource getDataSource(String datasetName) {
        return dataSources.computeIfAbsent(datasetName, name -> {
            File mbtilesFile = new File(properties.getDataDir(), name + ".mbtiles");
            if (!mbtilesFile.exists()) {
                log.error("MBTiles file not found: {}", mbtilesFile.getAbsolutePath());
                return null;
            }

            log.info("Initializing DataSource for: {}", mbtilesFile.getAbsolutePath());

            // 1. Ensure composite index exists (eliminates full-table-scan)
            //    Uses a separate one-shot connection so it doesn't interfere with the read pool
            ensureIndexExists(mbtilesFile);

            // 2. SQLite PRAGMAs tuned for maximum read throughput
            SQLiteConfig sqLiteConfig = new SQLiteConfig();
            sqLiteConfig.setReadOnly(true);
            sqLiteConfig.setCacheSize(10000);           // ~40 MB page cache
            sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
            sqLiteConfig.setSynchronous(SQLiteConfig.SynchronousMode.OFF);
            sqLiteConfig.setTempStore(SQLiteConfig.TempStore.MEMORY);
            sqLiteConfig.setPageSize(65536);            // 64 KB pages – better for large BLOBs
            // Enable memory-mapped I/O for the entire 2+ GB file
            // This lets the OS page cache handle I/O instead of SQLite's userspace buffers
            sqLiteConfig.setPragma(SQLiteConfig.Pragma.MMAP_SIZE, "2147483648"); // 2 GB mmap

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + mbtilesFile.getAbsolutePath());
            config.setDriverClassName("org.sqlite.JDBC");
            config.setDataSourceProperties(sqLiteConfig.toProperties());
            // Must match the driver's own read-only state to avoid IllegalStateException
            config.setReadOnly(true);
            // HTTP/2 multiplexing can push 30+ concurrent requests per connection
            // Increased pool size to handle bursts without pool exhaustion
            config.setMaximumPoolSize(40);
            config.setMinimumIdle(10);
            // Connection timeout: 30s gives slow queries time to complete under HTTP/2 load
            config.setConnectionTimeout(30000);
            // Max lifetime: recycle connections every 30 minutes to avoid stale file handles
            config.setMaxLifetime(1800000);
            // Enable keepalive to detect broken connections before they're reused
            config.setKeepaliveTime(60000);
            config.setPoolName("HikariCP-" + name);

            return new HikariDataSource(config);
        });
    }

    /**
     * Ensures (zoom_level, tile_column, tile_row) index exists.
     * A missing index causes full-table-scans → 1–8 s per tile.
     *
     * Also truncates any leftover WAL file to keep the database clean for read-only access.
     */
    private void ensureIndexExists(File mbtilesFile) {
        String url = "jdbc:sqlite:" + mbtilesFile.getAbsolutePath();
        try (Connection conn = java.sql.DriverManager.getConnection(url);
             java.sql.Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)");
            // Checkpoint and truncate WAL so the read-only pool starts with a clean slate
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            log.info("Verified/Created tile_index and cleaned WAL on {}", mbtilesFile.getName());
        } catch (Exception e) {
            log.warn("Could not ensure index on {}: {}", mbtilesFile.getName(), e.getMessage());
        }
    }

    /**
     * Returns a {@link TileEntry} for the requested tile.
     *
     * <p>The entry is cached by Caffeine so that:
     * <ul>
     *   <li>ETag is computed <em>once</em> at read time, never on subsequent requests.</li>
     *   <li>The gzip magic-byte check is performed <em>once</em> and stored.</li>
     *   <li>Repeated requests (pan-back, refresh) are served entirely from JVM heap.</li>
     * </ul>
     */
    @Cacheable(value = "tiles", key = "#datasetName + ':' + #z + ':' + #x + ':' + #y")
    public TileEntry getTile(String datasetName, int z, int x, int y) {
        DataSource dataSource = getDataSource(datasetName);
        if (dataSource == null) {
            return null;
        }

        // MBTiles uses TMS Y axis (origin bottom-left); XYZ has origin top-left
        int tmsY = (1 << z) - 1 - y;

        String sql = "SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, z);
            pstmt.setInt(2, x);
            pstmt.setInt(3, tmsY);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    byte[] data = rs.getBytes("tile_data");
                    // Pre-compute ETag and gzip flag once – cached together with the data
                    String etag = "\"" + Integer.toHexString(Arrays.hashCode(data)) + "\"";
                    boolean gzipped = data.length >= 2
                            && data[0] == (byte) 0x1F
                            && data[1] == (byte) 0x8B;
                    return new TileEntry(data, etag, gzipped);
                }
            }

        } catch (SQLException e) {
            log.error("Error querying tile {}/{}/{}/{}: {}", datasetName, z, x, y, e.getMessage());
        }

        return null;
    }

    /**
     * Reads the metadata table from the MBTiles file.
     * Returns key-value pairs (name, format, bounds, center, minzoom, maxzoom, etc.)
     */
    @Cacheable(value = "metadata", key = "#datasetName")
    public Map<String, String> getMetadata(String datasetName) {
        DataSource dataSource = getDataSource(datasetName);
        if (dataSource == null) {
            return Map.of();
        }

        Map<String, String> metadata = new java.util.LinkedHashMap<>();
        String sql = "SELECT name, value FROM metadata";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                metadata.put(rs.getString("name"), rs.getString("value"));
            }
        } catch (SQLException e) {
            log.warn("Error reading metadata for {}: {}", datasetName, e.getMessage());
        }
        return metadata;
    }

    /**
     * Returns the number of active datasources (for health checks).
     */
    public int getDataSourceCount() {
        return dataSources.size();
    }

    @PreDestroy
    public void cleanup() {
        log.info("Closing all MBTiles connections...");
        dataSources.values().forEach(HikariDataSource::close);
        dataSources.clear();
    }
}
