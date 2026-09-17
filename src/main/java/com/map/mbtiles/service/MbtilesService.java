package com.map.mbtiles.service;

import com.map.mbtiles.config.MbtilesProperties;
import com.map.mbtiles.model.DatasetInfo;
import com.map.mbtiles.model.TileEntry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.sqlite.SQLiteConfig;

import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * MBTiles 核心服务类
 * 负责 SQLite 连接池生命周期管理、多文件扩展名自适应、子目录递归发现、
 * PRAGMA 性能调优、连接池空闲自动缩容、批量预热拉取与空瓦片负向缓存
 */
@Service
public class MbtilesService {

    private static final Logger log = LoggerFactory.getLogger(MbtilesService.class);

    /** 数据集安全名称白名单正则：支持字母、数字、下划线、短横线以及子目录斜杠 */
    private static final Pattern SAFE_DATASET_NAME = Pattern.compile("^[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*$");

    /** 支持的 SQLite 矢量切片文件扩展名优先级列表 */
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".mbtiles",
            ".db",
            ".sqlite",
            ".sqlite3"
    );

    private final MbtilesProperties properties;
    private final CacheManager cacheManager;
    /** 每个数据集独立的 HikariCP 连接池映射 */
    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
    /** 数据集最近访问时间戳记录（用于海量数据源时的 LRU 淘汰） */
    private final Map<String, Long> lastAccessTimes = new ConcurrentHashMap<>();
    /** 数据集元数据结构体内存缓存 */
    private final Map<String, DatasetInfo> datasetInfoCache = new ConcurrentHashMap<>();

    public MbtilesService(MbtilesProperties properties, CacheManager cacheManager) {
        this.properties = properties;
        this.cacheManager = cacheManager;
    }

    /**
     * 将请求的数据集名称标准化（支持将双下划线 __ 映射为子目录斜杠 /）
     */
    public String normalizeDatasetName(String datasetName) {
        if (datasetName == null) {
            return null;
        }
        return datasetName.replace("__", "/").trim();
    }

    /**
     * 校验数据集名称合法性，防御路径遍历攻击（Path Traversal）
     */
    public boolean isValidDatasetName(String datasetName) {
        String normalized = normalizeDatasetName(datasetName);
        return normalized != null && SAFE_DATASET_NAME.matcher(normalized).matches();
    }

    /**
     * 安全解析并验证位于数据目录下的 MBTiles / DB 文件对象
     * 支持自适应匹配 .mbtiles、.db、.sqlite、.sqlite3 及子目录层级
     */
    public File getSafeDatasetFile(String datasetName) {
        if (!isValidDatasetName(datasetName)) {
            log.warn("数据集名称格式非法: {}", datasetName);
            return null;
        }

        String normalizedName = normalizeDatasetName(datasetName);

        try {
            File dataDir = new File(properties.getDataDir()).getCanonicalFile();
            if (!dataDir.exists() || !dataDir.isDirectory()) {
                return null;
            }

            // 1. 如果请求本身已携带合法扩展名，直接查找
            for (String ext : SUPPORTED_EXTENSIONS) {
                if (normalizedName.toLowerCase().endsWith(ext)) {
                    File directFile = new File(dataDir, normalizedName).getCanonicalFile();
                    if (isSafeFileUnderDir(directFile, dataDir)) {
                        return directFile;
                    }
                }
            }

            // 2. 依次尝试拼接各支持的扩展名（.mbtiles -> .db -> .sqlite -> .sqlite3）
            for (String ext : SUPPORTED_EXTENSIONS) {
                File candidate = new File(dataDir, normalizedName + ext).getCanonicalFile();
                if (isSafeFileUnderDir(candidate, dataDir)) {
                    return candidate;
                }
            }

            return null;
        } catch (IOException e) {
            log.warn("解析数据集文件路径异常 {}: {}", datasetName, e.getMessage());
            return null;
        }
    }

    /**
     * 严密校验文件是否真实存在且物理上严格限制在指定根目录内（防止软链接或目录穿越）
     */
    private boolean isSafeFileUnderDir(File file, File rootDir) {
        return file.exists() && file.isFile() && file.toPath().startsWith(rootDir.toPath());
    }

    /**
     * 获取（或懒加载初始化）指定数据集的高性能 HikariCP 连接池
     * 支持空闲自动缩容（minIdle=0，60秒自动释放连接）与超限 LRU 保护
     */
    public DataSource getDataSource(String datasetName) {
        if (!isValidDatasetName(datasetName)) {
            return null;
        }

        String normalizedName = normalizeDatasetName(datasetName);
        lastAccessTimes.put(normalizedName, System.currentTimeMillis());

        return dataSources.computeIfAbsent(normalizedName, name -> {
            File mbtilesFile = getSafeDatasetFile(name);
            if (mbtilesFile == null) {
                log.error("未找到对应的数据文件: {} (支持格式: .mbtiles, .db, .sqlite)", name);
                return null;
            }

            // 当数据源连接池总数超出保护阈值时，淘汰最久未访问的连接池，防止句柄耗尽
            evictOldestDataSourcesIfNecessary();

            log.info("正在为数据文件初始化独立连接池: {}", mbtilesFile.getAbsolutePath());

            // 1. 确保联合索引就绪并开启 WAL 日志模式
            ensureIndexAndWal(mbtilesFile);

            // 2. 配置针对只读高并发场景深度调优的 SQLite PRAGMA 参数
            SQLiteConfig sqLiteConfig = new SQLiteConfig();
            sqLiteConfig.setReadOnly(true);
            sqLiteConfig.setCacheSize(10000);           // 单连接 ~40 MB 内部页缓存
            sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
            sqLiteConfig.setSynchronous(SQLiteConfig.SynchronousMode.OFF);
            sqLiteConfig.setTempStore(SQLiteConfig.TempStore.MEMORY);
            // 开启 2GB 内存映射 I/O（mmap），充分利用操作系统的 Page Cache 减少内核态拷贝
            sqLiteConfig.setPragma(SQLiteConfig.Pragma.MMAP_SIZE, "2147483648");

            MbtilesProperties.PoolProperties poolProps = properties.getPool();

            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + mbtilesFile.getAbsolutePath());
            config.setDriverClassName("org.sqlite.JDBC");
            config.setDataSourceProperties(sqLiteConfig.toProperties());
            config.setReadOnly(true);
            config.setMaximumPoolSize(poolProps.getMaxSize());
            // 设为 0：当某个数据集空闲超过 idleTimeout 时，连接全部释放，节约海量文件句柄
            config.setMinimumIdle(poolProps.getMinIdle());
            config.setIdleTimeout(poolProps.getIdleTimeout());
            config.setConnectionTimeout(poolProps.getConnectionTimeout());
            config.setMaxLifetime(poolProps.getMaxLifetime());
            config.setPoolName("HikariCP-" + name.replace('/', '-'));

            return new HikariDataSource(config);
        });
    }

    /**
     * 当同时打开的活跃数据源数量超出上限时，安全回收最久未访问的连接池
     */
    private synchronized void evictOldestDataSourcesIfNecessary() {
        int maxActive = properties.getPool().getMaxActivePools();
        if (dataSources.size() < maxActive) {
            return;
        }

        // 查找最久未访问的数据源
        String oldestName = null;
        long oldestTime = Long.MAX_VALUE;

        for (Map.Entry<String, Long> entry : lastAccessTimes.entrySet()) {
            if (dataSources.containsKey(entry.getKey()) && entry.getValue() < oldestTime) {
                oldestTime = entry.getValue();
                oldestName = entry.getKey();
            }
        }

        if (oldestName != null) {
            log.info("活跃数据源数量达到阈值 ({})，正在回收最久未访问连接池: {}", maxActive, oldestName);
            HikariDataSource ds = dataSources.remove(oldestName);
            if (ds != null) {
                ds.close();
            }
            lastAccessTimes.remove(oldestName);
        }
    }

    /**
     * 确保数据库联合索引存在并激活 WAL 模式
     * 深度兼容 Schema A（tiles 扁平实体表）与 Schema B（通过 map + images 拼接的视图）
     */
    private void ensureIndexAndWal(File mbtilesFile) {
        String url = "jdbc:sqlite:" + mbtilesFile.getAbsolutePath();
        try (Connection conn = java.sql.DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            // 在可写初始化连接上显式开启 WAL 模式
            stmt.execute("PRAGMA journal_mode = WAL");

            // 检查 'tiles' 属于实体表还是视图
            String tilesType = null;
            try (ResultSet rs = stmt.executeQuery("SELECT type FROM sqlite_master WHERE name = 'tiles'")) {
                if (rs.next()) {
                    tilesType = rs.getString("type");
                }
            }

            if ("view".equalsIgnoreCase(tilesType)) {
                log.info("{} 采用 MBTiles 视图结构（VIEW），在底层 map 和 images 表上建立索引", mbtilesFile.getName());
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS map_tile_idx ON map (zoom_level, tile_column, tile_row)");
                stmt.execute("CREATE INDEX IF NOT EXISTS images_tile_id_idx ON images (tile_id)");
            } else {
                log.info("{} 采用 MBTiles 实体表结构（TABLE），在 tiles 表上建立联合索引", mbtilesFile.getName());
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS tile_index ON tiles (zoom_level, tile_column, tile_row)");
            }

            // 执行检查点截断 WAL，确保只读连接池在一个整洁的状态下启动
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            log.info("已成功校验/创建索引并清理 WAL: {}", mbtilesFile.getName());
        } catch (Exception e) {
            log.warn("检查或初始化索引/WAL失败 {}: {}", mbtilesFile.getName(), e.getMessage());
        }
    }

    /**
     * 获取结构化数据集元数据
     */
    public DatasetInfo getDatasetInfo(String datasetName) {
        if (!isValidDatasetName(datasetName)) {
            return null;
        }

        String normalizedName = normalizeDatasetName(datasetName);

        return datasetInfoCache.computeIfAbsent(normalizedName, name -> {
            File file = getSafeDatasetFile(name);
            if (file == null) {
                return null;
            }
            Map<String, String> meta = getMetadata(name);
            return DatasetInfo.fromMetadata(name, meta, file.length(), file.lastModified());
        });
    }

    /**
     * 递归扫描数据存储目录（包括子目录），发现所有 .mbtiles, .db, .sqlite 数据集
     */
    public List<DatasetInfo> listDatasets() {
        File dataDir = new File(properties.getDataDir());
        if (!dataDir.exists() || !dataDir.isDirectory()) {
            return Collections.emptyList();
        }

        List<DatasetInfo> result = new ArrayList<>();
        Path rootPath = dataDir.toPath();

        try (Stream<Path> walk = Files.walk(rootPath, 5)) {
            List<Path> candidateFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String fileName = p.getFileName().toString().toLowerCase();
                        return SUPPORTED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
                    })
                    .toList();

            for (Path p : candidateFiles) {
                Path relative = rootPath.relativize(p);
                String relativeStr = relative.toString().replace('\\', '/');

                // 去除已知扩展名作为数据集路由名称
                String datasetName = relativeStr;
                for (String ext : SUPPORTED_EXTENSIONS) {
                    if (datasetName.toLowerCase().endsWith(ext)) {
                        datasetName = datasetName.substring(0, datasetName.length() - ext.length());
                        break;
                    }
                }

                DatasetInfo info = getDatasetInfo(datasetName);
                if (info != null) {
                    result.add(info);
                }
            }
        } catch (IOException e) {
            log.warn("扫描数据目录失败 {}: {}", properties.getDataDir(), e.getMessage());
        }

        return result;
    }

    /**
     * 获取指定坐标的矢量瓦片包装对象
     * 优先走 Caffeine 缓存；若未命中则查询 SQLite。
     * 若 SQLite 中不存在该坐标数据，则返回并缓存 TileEntry.EMPTY 单例，杜绝大范围空白区域的穿透查询。
     */
    @Cacheable(value = "tiles", key = "#datasetName + ':' + #z + ':' + #x + ':' + #y")
    public TileEntry getTile(String datasetName, int z, int x, int y) {
        DataSource dataSource = getDataSource(datasetName);
        if (dataSource == null) {
            return TileEntry.EMPTY;
        }

        // MBTiles 标准采用 TMS 坐标系（原点在左下角），而 Web 请求为 XYZ 坐标系（原点在左上角），需进行 Y 轴转换
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
                        return TileEntry.EMPTY;
                    }

                    // 采用硬件 SIMD 指令加速的 CRC32 计算 ETag
                    CRC32 crc = new CRC32();
                    crc.update(data);
                    String etag = "\"" + Long.toHexString(crc.getValue()) + "\"";

                    // 检查是否为 Gzip 压缩魔数 (0x1F, 0x8B)
                    boolean gzipped = data.length >= 2
                            && data[0] == (byte) 0x1F
                            && data[1] == (byte) 0x8B;
                    return new TileEntry(data, etag, gzipped);
                }
            }

        } catch (SQLException e) {
            log.error("查询瓦片出错 {}/{}/{}/{}: {}", datasetName, z, x, y, e.getMessage());
        }

        // 数据库查无此瓦片，返回空瓦片单例写入缓存，防止后续重复穿透 SQLite
        return TileEntry.EMPTY;
    }

    /**
     * 单条范围 SQL 批量预加载指定层级以内的所有瓦片到 Caffeine 缓存
     * 彻底消除成千上万次独立的 JDBC 单查往返
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

                    // TMS 坐标转换为 XYZ 坐标
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
            log.error("数据集 {} 批量预热失败: {}", datasetName, e.getMessage());
        }

        return loaded;
    }

    /**
     * 读取 MBTiles / DB 文件中的 metadata 元数据表键值对
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
            log.warn("读取数据集 {} 元数据异常: {}", datasetName, e.getMessage());
        }
        return metadata;
    }

    /**
     * 获取 Caffeine 瓦片缓存的运行指标统计数据
     */
    public Map<String, Object> getCacheStats() {
        Cache cache = cacheManager.getCache("tiles");
        if (cache != null && cache.getNativeCache() instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> nativeCache) {
            com.github.benmanes.caffeine.cache.stats.CacheStats stats = nativeCache.stats();
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("estimatedSize", nativeCache.estimatedSize());
            map.put("hitCount", stats.hitCount());
            map.put("missCount", stats.missCount());
            double hitRate = stats.requestCount() > 0 ? stats.hitRate() * 100.0 : 0.0;
            map.put("hitRate", String.format("%.2f%%", hitRate));
            map.put("evictionCount", stats.evictionCount());
            map.put("loadSuccessCount", stats.loadSuccessCount());
            map.put("activeDataSources", getDataSourceCount());
            return map;
        }
        return Map.of("status", "缓存统计指标未开启或不可用");
    }

    /**
     * 热重载所有数据集：释放全部连接池、清空内存缓存，并在后续请求时按需重新加载
     */
    public synchronized void reloadDatasets() {
        log.info("正在执行 MBTiles 数据集热重载...");
        cleanup();
        Cache tileCache = cacheManager.getCache("tiles");
        if (tileCache != null) {
            tileCache.clear();
        }
        Cache metaCache = cacheManager.getCache("metadata");
        if (metaCache != null) {
            metaCache.clear();
        }
        log.info("所有 MBTiles 连接池与内存缓存已重置完毕。");
    }

    /**
     * 获取当前活跃的数据源连接池数量（用于健康监控）
     */
    public int getDataSourceCount() {
        return dataSources.size();
    }

    /**
     * 容器销毁前优雅关闭所有 HikariCP 连接池并释放文件句柄
     */
    @PreDestroy
    public void cleanup() {
        log.info("正在关闭所有 MBTiles 数据库连接池...");
        dataSources.values().forEach(HikariDataSource::close);
        dataSources.clear();
        lastAccessTimes.clear();
        datasetInfoCache.clear();
    }
}
