package com.map.mbtiles.service;

import com.map.mbtiles.config.MbtilesProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.sqlite.SQLiteConfig;

import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

@Slf4j
@Service
public class MbtilesService {

    private static final Pattern SAFE_DATASET_NAME = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private final MbtilesProperties properties;
    private final CacheManager cacheManager;
    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
    private final Map<String, DatasetInfo> datasetInfoCache = new ConcurrentHashMap<>();

    public MbtilesService(MbtilesProperties properties, CacheManager cacheManager) {
        this.properties = properties;
        this.cacheManager = cacheManager;
    }

    /**
     * Validates dataset name against directory traversal attacks.
     */
    public boolean isValidDatasetName(String datasetName) {
        return datasetName != null && SAFE_DATASET_NAME.matcher(datasetName).matches();
    }

    /**
     * Resolves and verifies the MBTiles file within data directory safely.
     */
    public File getSafeDatasetFile(String datasetName) {
        if (!isValidDatasetName(datasetName)) {
            log.warn("Invalid dataset name format: {}", datasetName);
            return null;
        }

        try {
            File dataDir = new File(properties.getDataDir()).getCanonicalFile();
            File mbtilesFile = new File(dataDir, datasetName + ".mbtiles").getCanonicalFile();

            if (!mbtilesFile.toPath().startsWith(dataDir.toPath())) {
                log.warn("Path traversal attempt detected for dataset: {}", datasetName);
                return null;
            }

            if (!mbtilesFile.exists() || !mbtilesFile.isFile()) {
                return null;
            }

            return mbtilesFile;
        } catch (IOException e) {
            log.warn("Error resolving file path for {}: {}", datasetName, e.getMessage());
            return null;
        }
    }

    /**
     * Gets (or lazily initialises) the connection pool for a given dataset.
     */
    public DataSource getDataSource(String datasetName) {
        if (!isValidDatasetName(datasetName)) {
            return null;
        }

        return dataSources.computeIfAbsent(datasetName, name -> {
            File mbtilesFile = getSafeDatasetFile(name);
            if (mbtilesFile == null) {
                log.error("MBTiles file not found or invalid: {} under {}", name, properties.getDataDir());
                return null;
            }

            log.info("Initializing DataSource for: {}", mbtilesFile.getAbsolutePath());

            // 1. Ensure appropriate indexes exist and WAL mode is active
            ensureIndexAndWal(mbtilesFile);

            // 2. SQLite PRAGMAs tuned for maximum read throughput
            SQLiteConfig sqLiteConfig = new SQLiteConfig();
            sqLiteConfig.setReadOnly(true);
            sqLiteConfig.setCacheSize(10000);           // ~40 MB page cache per connection
            sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
            sqLiteConfig.setSynchronous(SQLiteConfig.SynchronousMode.OFF);
            sqLiteConfig.setTempStore(SQLiteConfig.TempStore.MEMORY);
            // Enable memory-mapped I/O for 2 GB to leverage OS page cache
            sqLiteConfig.setPragma(SQLiteConfig.Pragma.MMAP_SIZE, "2147483648");

            MbtilesProperties.PoolProperties poolProps = properties.getPool();

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + mbtilesFile.getAbsolutePath());
            config.setDriverClassName("org.sqlite.JDBC");
            config.setDataSourceProperties(sqLiteConfig.toProperties());
            config.setReadOnly(true);
            config.setMaximumPoolSize(poolProps.getMaxSize());
            config.setMinimumIdle(poolProps.getMinIdle());
            config.setConnectionTimeout(poolProps.getConnectionTimeout());
            config.setMaxLifetime(poolProps.getMaxLifetime());
            config.setPoolName("HikariCP-" + name);

            return new HikariDataSource(config);
        });
    }

    /**
     * Ensures composite indexes exist and WAL mode is turned on.
     * Compatible with both Schema A (tiles table) and Schema B (tiles view over map + images).
     */
    private void ensureIndexAndWal(File mbtilesFile) {
        String url = "jdbc:sqlite:" + mbtilesFile.getAbsolutePath();
        try (Connection conn = java.sql.DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            // Enable WAL mode on read-write connection first
            stmt.execute("PRAGMA journal_mode = WAL");

            // Check if 'tiles' is a view or a table
            String tilesType = null;
            try (ResultSet rs = stmt.executeQuery("SELECT type FROM sqlite_master WHERE name = 'tiles'")) {
                if (rs.next()) {
                    tilesType = rs.getString("type");
                }
            }

            if ("view".equalsIgnoreCase(tilesType)) {
                log.info("{} uses MBTiles VIEW schema; creating indexes on map and images tables", mbtilesFile.getName());
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS map_tile_idx ON map (zoom_level, tile_column, tile_row)");
                stmt.execute("CREATE INDEX IF NOT EXISTS images_tile_id_idx ON images (tile_id)");
            } else {
                log.info("{} uses MBTiles TABLE schema; creating index on tiles table", mbtilesFile.getName());
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)");
            }

            // Checkpoint and truncate WAL so read pool starts cleanly
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            log.info("Verified/Created indexes and checkpointed WAL for {}", mbtilesFile.getName());
        } catch (Exception e) {
            log.warn("Could not ensure index or WAL on {}: {}", mbtilesFile.getName(), e.getMessage());
        }
    }

    /**
     * Returns structured dataset metadata.
     */
    public DatasetInfo getDatasetInfo(String datasetName) {
        if (!isValidDatasetName(datasetName)) {
            return null;
        }

        return datasetInfoCache.computeIfAbsent(datasetName, name -> {
            File file = getSafeDatasetFile(name);
            if (file == null) {
                return null;
            }
            Map<String, String> meta = getMetadata(name);
            return DatasetInfo.fromMetadata(name, meta, file.length(), file.lastModified());
        });
    }

    /**
     * Scans the data directory and lists all available datasets with metadata summaries.
     */
    public List<DatasetInfo> listDatasets() {
        File dataDir = new File(properties.getDataDir());
        if (!dataDir.exists() || !dataDir.isDirectory()) {
            return Collections.emptyList();
        }

        File[] files = dataDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".mbtiles"));
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }

        List<DatasetInfo> result = new ArrayList<>();
        for (File f : files) {
            String name = f.getName().substring(0, f.getName().length() - 8);
            DatasetInfo info = getDatasetInfo(name);
            if (info != null) {
                result.add(info);
            }
        }
        return result;
    }

    /**
     * Returns a {@link TileEntry} for the requested tile.
     * Checks Zoom validity against metadata before touching the database.
     */
    @Cacheable(value = "tiles", key = "#datasetName + ':' + #z + ':' + #x + ':' + #y", unless = "#result == null")
    public TileEntry getTile(String datasetName, int z, int x, int y) {
        // Zoom range short-circuit
        DatasetInfo info = getDatasetInfo(datasetName);
        if (info != null && !info.isZoomValid(z)) {
            return null;
        }

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
                    if (data == null || data.length == 0) {
                        return null;
                    }

                    // Hardware-accelerated CRC32 ETag
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    String etag = "\"" + Long.toHexString(crc.getValue()) + "\"";

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
     * Batch warms up tiles up to maxZoom in a single range query, avoiding thousands of sequential queries.
     * Directly populates Caffeine cache.
     *
     * @return Number of tiles preloaded into cache.
     */
    public int warmupDatasetBatch(String datasetName, int maxZoom) {
        DataSource dataSource = getDataSource(datasetName);
        if (dataSource == null) {
            return 0;
        }

        Cache tileCache = cacheManager.getCache("tiles");
        String sql = "SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles WHERE zoom_level <= ?";
        int loaded = 0;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, maxZoom);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    int z = rs.getInt(1);
                    int x = rs.getInt(2);
                    int tileRow = rs.getInt(3);
                    byte[] data = rs.getBytes(4);

                    if (data == null || data.length == 0) {
                        continue;
                    }

                    // Convert TMS row to XYZ y
                    int y = (1 << z) - 1 - tileRow;

                    CRC32 crc = new CRC32();
                    crc.update(data);
                    String etag = "\"" + Long.toHexString(crc.getValue()) + "\"";

                    boolean gzipped = data.length >= 2
                            && data[0] == (byte) 0x1F
                            && data[1] == (byte) 0x8B;

                    TileEntry entry = new TileEntry(data, etag, gzipped);

                    if (tileCache != null) {
                        tileCache.put(datasetName + ":" + z + ":" + x + ":" + y, entry);
                    }
                    loaded++;
                }
            }
        } catch (SQLException e) {
            log.error("Batch warmup error for dataset {}: {}", datasetName, e.getMessage());
        }

        return loaded;
    }

    /**
     * Reads the metadata table from the MBTiles file.
     */
    @Cacheable(value = "metadata", key = "#datasetName")
    public Map<String, String> getMetadata(String datasetName) {
        DataSource dataSource = getDataSource(datasetName);
        if (dataSource == null) {
            return Collections.emptyMap();
        }

        Map<String, String> metadata = new LinkedHashMap<>();
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
        datasetInfoCache.clear();
    }
}
