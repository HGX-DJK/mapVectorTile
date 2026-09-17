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

/**
 * MBTiles 核心服务类
 * 负责 SQLite 连接池生命周期管理、PRAGMA 性能调优、批量预热拉取以及带缓存的瓦片读取
 */
@Slf4j
@Service
public class MbtilesService {

    /** 数据集安全名称白名单正则：仅允许字母、数字、下划线及短横线 */
    private static final Pattern SAFE_DATASET_NAME = Pattern.compile("^[a-zA-Z0-9_-]+$");

    private final MbtilesProperties properties;
    private final CacheManager cacheManager;
    /** 每个数据集独立的 HikariCP 连接池映射 */
    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
    /** 数据集元数据结构体内存缓存 */
    private final Map<String, DatasetInfo> datasetInfoCache = new ConcurrentHashMap<>();

    public MbtilesService(MbtilesProperties properties, CacheManager cacheManager) {
        this.properties = properties;
        this.cacheManager = cacheManager;
    }

    /**
     * 校验数据集名称合法性，防御路径遍历攻击（Path Traversal）
     */
    public boolean isValidDatasetName(String datasetName) {
        return datasetName != null && SAFE_DATASET_NAME.matcher(datasetName).matches();
    }

    /**
     * 安全解析并验证位于数据目录下的 MBTiles 文件对象
     */
    public File getSafeDatasetFile(String datasetName) {
        if (!isValidDatasetName(datasetName)) {
            log.warn("数据集名称格式非法: {}", datasetName);
            return null;
        }

        try {
            File dataDir = new File(properties.getDataDir()).getCanonicalFile();
            File mbtilesFile = new File(dataDir, datasetName + ".mbtiles").getCanonicalFile();

            // 严密校验是否脱离了配置的根数据目录
            if (!mbtilesFile.toPath().startsWith(dataDir.toPath())) {
                log.warn("检测到恶意路径穿越行为，数据集名称: {}", datasetName);
                return null;
            }

            if (!mbtilesFile.exists() || !mbtilesFile.isFile()) {
                return null;
            }

            return mbtilesFile;
        } catch (IOException e) {
            log.warn("解析数据集文件路径异常 {}: {}", datasetName, e.getMessage());
            return null;
        }
    }

    /**
     * 获取（或懒加载初始化）指定数据集的高性能 HikariCP 连接池
     */
    public DataSource getDataSource(String datasetName) {
        if (!isValidDatasetName(datasetName)) {
            return null;
        }

        return dataSources.computeIfAbsent(datasetName, name -> {
            File mbtilesFile = getSafeDatasetFile(name);
            if (mbtilesFile == null) {
                log.error("未找到 MBTiles 文件或路径非法: {} (目录: {})", name, properties.getDataDir());
                return null;
            }

            log.info("正在为数据集初始化数据库连接池: {}", mbtilesFile.getAbsolutePath());

            // 1. 确保联合索引就绪并开启 WAL 日志模式
            ensureIndexAndWal(mbtilesFile);

            // 2. 配置针对只读高并发场景深度调优的 SQLite PRAGMA 参数
            SQLiteConfig sqLiteConfig = new SQLiteConfig();
            sqLiteConfig.setReadOnly(true);
            sqLiteConfig.setCacheSize(10000);           // 单连接 ~40 MB 内部页缓存
            sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
            sqLiteConfig.setSynchronous(SQLiteConfig.SynchronousMode.OFF);
            sqLiteConfig.setTempStore(SQLiteConfig.TempStore.MEMORY);
            // 开启 2GB 内存映射 I/O（mmap），充分利用操作系统的 Page Cache 减少用户态/内核态拷贝
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
     * 扫描数据存储目录，返回所有可用数据集的元数据概览列表
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
     * 获取指定坐标的矢量瓦片包装对象
     * 优先走 Caffeine 缓存；若未命中则查询 SQLite，并自动做 Zoom 边界前置短路判断
     */
    @Cacheable(value = "tiles", key = "#datasetName + ':' + #z + ':' + #x + ':' + #y", unless = "#result == null")
    public TileEntry getTile(String datasetName, int z, int x, int y) {
        // 缩放层级前置短路过滤：若超出该数据集的缩放范围，不触发任何数据库 I/O 直接返回 null
        DatasetInfo info = getDatasetInfo(datasetName);
        if (info != null && !info.isZoomValid(z)) {
            return null;
        }

        DataSource dataSource = getDataSource(datasetName);
        if (dataSource == null) {
            return null;
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
                        return null;
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

        return null;
    }

    /**
     * 单条范围 SQL 批量预加载指定层级以内的所有瓦片到 Caffeine 缓存
     * 彻底消除成千上万次独立的 JDBC 单查往返
     *
     * @param datasetName 数据集名称
     * @param maxZoom     最大预热层级
     * @return 实际成功写入缓存的瓦片总数
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
     * 读取 MBTiles 文件中的 metadata 元数据表键值对
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
        datasetInfoCache.clear();
    }
}
