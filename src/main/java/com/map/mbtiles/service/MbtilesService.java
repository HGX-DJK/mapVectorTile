package com.map.mbtiles.service;

import com.map.mbtiles.config.MbtilesProperties;
import com.map.mbtiles.model.DatasetInfo;
import com.map.mbtiles.model.TableSchema;
import com.map.mbtiles.model.TileEntry;
import com.map.mbtiles.model.TileScheme;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.sqlite.SQLiteConfig;

import javax.annotation.PreDestroy;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList(
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
    /** 每个数据集确定或已自愈锁定的坐标系规范 (TMS / XYZ) 映射 */
    private final Map<String, TileScheme> datasetSchemes = new ConcurrentHashMap<>();
    /** 记录已在 metadata 中显式硬性声明或已成功自愈锁定的数据集集合 */
    private final Set<String> lockedSchemes = ConcurrentHashMap.newKeySet();
    /** 每个数据集确定或自适应探测识别出的 SQLite 表结构与字段元信息映射 */
    private final Map<String, TableSchema> datasetTableSchemas = new ConcurrentHashMap<>();

    public MbtilesService(MbtilesProperties properties, CacheManager cacheManager) {
        this.properties = properties;
        this.cacheManager = cacheManager;
    }

    /**
     * 将请求的数据集名称标准化
     * 1. 支持将双下划线 __ 映射为子目录斜杠 /
     * 2. 自动剥离已知扩展名（.mbtiles、.db、.sqlite、.sqlite3），使携带后缀的请求能够安全通过白名单校验
     */
    public String normalizeDatasetName(String datasetName) {
        if (datasetName == null) {
            return null;
        }
        String normalized = datasetName.replace("__", "/").trim();
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (normalized.toLowerCase().endsWith(ext)) {
                normalized = normalized.substring(0, normalized.length() - ext.length());
                break;
            }
        }
        return normalized;
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

        try {
            File dataDir = new File(properties.getDataDir()).getCanonicalFile();
            if (!dataDir.exists() || !dataDir.isDirectory()) {
                return null;
            }

            // 1. 如果原始请求中已包含支持的扩展名，优先直接精准匹配物理文件
            String rawPath = datasetName.replace("__", "/").trim();
            for (String ext : SUPPORTED_EXTENSIONS) {
                if (rawPath.toLowerCase().endsWith(ext)) {
                    File directFile = new File(dataDir, rawPath).getCanonicalFile();
                    if (isSafeFileUnderDir(directFile, dataDir)) {
                        return directFile;
                    }
                }
            }

            // 2. 依次尝试拼接各支持的扩展名（.mbtiles -> .db -> .sqlite -> .sqlite3）
            String normalizedName = normalizeDatasetName(datasetName);
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
            if (mbtilesFile.canWrite()) {
                sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
                sqLiteConfig.setSynchronous(SQLiteConfig.SynchronousMode.OFF);
            } else {
                // 只读介质（如 Docker :ro 挂载或只读文件）关闭 WAL 日志写入，杜绝只读异常
                sqLiteConfig.setJournalMode(SQLiteConfig.JournalMode.OFF);
            }
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
     * 深度兼容标准 tiles 实体表、视图（map + images）以及非标 grids 表
     */
    private void ensureIndexAndWal(File mbtilesFile) {
        if (!mbtilesFile.canWrite()) {
            log.info("切片数据文件处于只读模式/只读介质，安全跳过索引校验与 WAL 模式写入: {}", mbtilesFile.getName());
            return;
        }

        String url = "jdbc:sqlite:" + mbtilesFile.getAbsolutePath();
        try (Connection conn = java.sql.DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {

            // 在可写初始化连接上显式开启 WAL 模式
            stmt.execute("PRAGMA journal_mode = WAL");

            // 自适应解析该数据库的表结构元信息 (tiles 或 grids 等)
            TableSchema schema = getTableSchema(mbtilesFile.getName(), conn);

            // 检查对应表属于实体表还是视图
            String tableType = null;
            try (ResultSet rs = stmt.executeQuery("SELECT type FROM sqlite_master WHERE name = '" + schema.tableName() + "'")) {
                if (rs.next()) {
                    tableType = rs.getString("type");
                }
            }

            if ("view".equalsIgnoreCase(tableType)) {
                log.info("{} 采用 MBTiles 视图结构（VIEW: {}），在底层 map 和 images 表上建立索引", mbtilesFile.getName(), schema.tableName());
                stmt.execute("CREATE UNIQUE INDEX IF NOT EXISTS map_tile_idx ON map (zoom_level, tile_column, tile_row)");
                stmt.execute("CREATE INDEX IF NOT EXISTS images_tile_id_idx ON images (tile_id)");
            } else {
                log.info("{} 采用实体表结构（TABLE: {}），在对应列上建立联合索引", mbtilesFile.getName(), schema.tableName());
                stmt.execute(schema.createIndexSql());
            }

            // 执行检查点截断 WAL，确保只读连接池在一个整洁的状态下启动
            stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
            log.info("已成功校验/创建索引并清理 WAL: {}", mbtilesFile.getName());
        } catch (Exception e) {
            log.warn("检查或初始化索引/WAL失败 {}: {}", mbtilesFile.getName(), e.getMessage());
        }
    }

    /**
     * 获取（或自适应探测识别）指定数据集的底层 SQLite 表结构（支持 yml 个性化配置与自动探测）
     */
    public TableSchema getTableSchema(String datasetName, Connection conn) {
        String normalizedName = normalizeDatasetName(datasetName);
        if (normalizedName == null) {
            return TableSchema.DEFAULT;
        }

        TableSchema cached = datasetTableSchemas.get(normalizedName);
        if (cached != null) {
            return cached;
        }

        // 1. 检查 application.yml 中是否对该数据集配置了个性化覆盖规则
        MbtilesProperties.SchemaProperties schemaProps = properties.getSchema();
        MbtilesProperties.CustomDatasetSchema custom = schemaProps.getDatasetOverrides().get(normalizedName);
        if (custom != null && custom.getTableName() != null && custom.getDataColumn() != null) {
            TableSchema customSchema = new TableSchema(
                    custom.getTableName(),
                    custom.getZoomColumn() != null ? custom.getZoomColumn() : "zoom_level",
                    custom.getColColumn() != null ? custom.getColColumn() : "tile_column",
                    custom.getRowColumn() != null ? custom.getRowColumn() : "tile_row",
                    custom.getDataColumn()
            );
            datasetTableSchemas.put(normalizedName, customSchema);
            log.info("数据集 '{}' 采用 yml 中配置的自定义表结构: 表名 [{}], 瓦片字段 [{}], 坐标字段 [{}, {}, {}]",
                    normalizedName, customSchema.tableName(), customSchema.dataCol(),
                    customSchema.zoomCol(), customSchema.colCol(), customSchema.rowCol());
            return customSchema;
        }

        // 2. 动态读取 SQLite 元数据字典自适应探测
        TableSchema detected = detectTableSchemaFromDb(conn, normalizedName, schemaProps);
        datasetTableSchemas.put(normalizedName, detected);
        return detected;
    }

    /**
     * 读取 SQLite 元数据字典，比对配置的候选表名与候选列名
     */
    private TableSchema detectTableSchemaFromDb(Connection conn, String datasetName, MbtilesProperties.SchemaProperties schemaProps) {
        if (conn == null) {
            return TableSchema.DEFAULT;
        }

        try (Statement stmt = conn.createStatement()) {
            // 2.1 获取数据库中所有存在的表和视图名
            List<String> existingTables = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery("SELECT name FROM sqlite_master WHERE type IN ('table', 'view')")) {
                while (rs.next()) {
                    existingTables.add(rs.getString(1).toLowerCase());
                }
            }

            // 2.2 按候选表名优先级寻找首个匹配的表名
            String matchedTable = null;
            for (String candidate : schemaProps.getCandidateTableNames()) {
                if (existingTables.contains(candidate.toLowerCase())) {
                    matchedTable = candidate;
                    break;
                }
            }

            if (matchedTable == null) {
                // 候选列表均未匹配，兜底采用默认 tiles
                return TableSchema.DEFAULT;
            }

            // 2.3 获取该表的所有列名
            List<String> columns = new ArrayList<>();
            try (ResultSet rs = stmt.executeQuery("PRAGMA table_info(" + matchedTable + ")")) {
                while (rs.next()) {
                    columns.add(rs.getString("name").toLowerCase());
                }
            }

            String dataCol = findFirstMatch(columns, schemaProps.getCandidateDataColumns(), "tile_data");
            String zoomCol = findFirstMatch(columns, schemaProps.getCandidateZoomColumns(), "zoom_level");
            String colCol = findFirstMatch(columns, schemaProps.getCandidateColumnColumns(), "tile_column");
            String rowCol = findFirstMatch(columns, schemaProps.getCandidateRowColumns(), "tile_row");

            TableSchema schema = new TableSchema(matchedTable, zoomCol, colCol, rowCol, dataCol);
            log.info("数据集 '{}' 自动探测识别表结构: 表名 [{}], 瓦片字段 [{}], 坐标字段 [{}, {}, {}]",
                    datasetName, matchedTable, dataCol, zoomCol, colCol, rowCol);
            return schema;

        } catch (SQLException e) {
            log.warn("探测数据集 '{}' 表结构失败: {}, 回退到默认表结构", datasetName, e.getMessage());
            return TableSchema.DEFAULT;
        }
    }

    private String findFirstMatch(List<String> actualColumns, List<String> candidates, String fallback) {
        if (candidates != null) {
            for (String candidate : candidates) {
                if (actualColumns.contains(candidate.toLowerCase())) {
                    return candidate;
                }
            }
        }
        return fallback;
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
                    .collect(Collectors.toList());

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
     * 解析或获取指定数据集的坐标系规范（优先检查 metadata 声明，默认回退 TMS 规范）
     */
    public TileScheme getDatasetScheme(String datasetName) {
        String normalizedName = normalizeDatasetName(datasetName);
        if (normalizedName == null) {
            return TileScheme.TMS;
        }

        return datasetSchemes.computeIfAbsent(normalizedName, name -> {
            Map<String, String> meta = getMetadata(name);
            if (meta != null && meta.containsKey("scheme")) {
                TileScheme declared = TileScheme.fromString(meta.get("scheme"));
                if (declared != null) {
                    lockedSchemes.add(name);
                    log.info("数据集 '{}' 从 metadata 中检测到显式坐标系规范: {}", name, declared);
                    return declared;
                }
            }
            // 默认遵循 MBTiles 官方规范 (TMS 左下角原点)
            return TileScheme.TMS;
        });
    }

    /**
     * 从 SQLite 瓦片表中查询单瓦片二进制数据
     */
    private byte[] queryTileData(Connection conn, String sql, int z, int x, int row) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, z);
            pstmt.setInt(2, x);
            pstmt.setInt(3, row);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getBytes(1);
                }
            }
        }
        return null;
    }

    /**
     * 获取指定坐标的矢量瓦片包装对象
     * 优先走 Caffeine 缓存；若未命中则查询 SQLite。
     * 支持 TMS（左下角）与 XYZ（左上角）多坐标系自适应查询与反向自愈锁定。
     * 若 SQLite 中不存在该坐标数据，则返回并缓存 TileEntry.EMPTY 单例，杜绝空白网格穿透。
     */
    @Cacheable(value = "tiles", key = "#datasetName + ':' + #z + ':' + #x + ':' + #y")
    public TileEntry getTile(String datasetName, int z, int x, int y) {
        DataSource dataSource = getDataSource(datasetName);
        if (dataSource == null) {
            return TileEntry.EMPTY;
        }

        String normalizedName = normalizeDatasetName(datasetName);
        TileScheme scheme = getDatasetScheme(normalizedName);
        int targetRow = scheme.toDatabaseRow(z, y);

        try (Connection conn = dataSource.getConnection()) {
            TableSchema schema = getTableSchema(normalizedName, conn);
            String sql = schema.selectTileSql();
            byte[] data = queryTileData(conn, sql, z, x, targetRow);

            // 若主推坐标未查出数据，且当前数据集尚未锁定坐标系，触发智能自愈反向探测
            if ((data == null || data.length == 0) && !lockedSchemes.contains(normalizedName)) {
                int alternateRow = (scheme == TileScheme.TMS) ? y : ((1 << z) - 1 - y);
                byte[] altData = queryTileData(conn, sql, z, x, alternateRow);
                if (altData != null && altData.length > 0) {
                    TileScheme corrected = (scheme == TileScheme.TMS) ? TileScheme.XYZ : TileScheme.TMS;
                    datasetSchemes.put(normalizedName, corrected);
                    lockedSchemes.add(normalizedName);
                    log.info("数据集 '{}' 查无 {} 瓦片但在反向坐标命中，已自动识别并自愈锁定为 {} 坐标系 (左{}角原点)",
                            normalizedName, scheme, corrected, corrected == TileScheme.XYZ ? "上" : "下");
                    data = altData;
                } else {
                    // 反向亦无数据，确为真实空白区域，锁定当前默认坐标系避免反复二次双查
                    lockedSchemes.add(normalizedName);
                }
            }

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

        } catch (SQLException e) {
            log.error("查询瓦片出错 {}/{}/{}/{}: {}", datasetName, z, x, y, e.getMessage());
        }

        // 数据库查无此瓦片，返回空瓦片单例写入缓存，防止后续重复穿透 SQLite
        return TileEntry.EMPTY;
    }

    /**
     * 单条范围 SQL 批量预加载指定层级以内的所有瓦片到 Caffeine 缓存
     * 彻底消除成千上万次独立的 JDBC 单查往返，自适应识别 TMS/XYZ 行号并还原为 Web XYZ y
     */
    public int warmupDatasetBatch(String datasetName, int maxZoom) {
        DataSource dataSource = getDataSource(datasetName);
        if (dataSource == null) {
            return 0;
        }

        TileScheme scheme = getDatasetScheme(datasetName);
        Cache tileCache = cacheManager.getCache("tiles");
        int loaded = 0;

        try (Connection conn = dataSource.getConnection()) {
            TableSchema schema = getTableSchema(datasetName, conn);
            String sql = schema.selectWarmupSql();

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
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

                    // 根据该数据集坐标系将数据库 tileRow 准确还原为 Web XYZ y
                    int y = scheme.toWebY(z, tileRow);

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
                // 使用直接列索引 1、2 替代字符串列名检索
                metadata.put(rs.getString(1), rs.getString(2));
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
        if (cache != null && cache.getNativeCache() instanceof com.github.benmanes.caffeine.cache.Cache) {
            com.github.benmanes.caffeine.cache.Cache<?, ?> nativeCache = (com.github.benmanes.caffeine.cache.Cache<?, ?>) cache.getNativeCache();
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
        return Collections.singletonMap("status", "缓存统计指标未开启或不可用");
    }

    /**
     * 细粒度独立热重载单个数据集：
     * 1. 安全关闭并移除该数据集的 HikariDataSource 连接池
     * 2. 移除其元数据缓存（datasetInfoCache 与 Spring metadata Cache）
     * 3. 精准驱逐 Caffeine 中属于该数据集前缀的所有已缓存瓦片，保留其他数据集的高命中率缓存（防雪崩）
     * 4. 若开启预热，异步触发该单数据集低层级切片的重新预热
     *
     * @param datasetName 待重载的数据集名称（支持携带扩展名或子目录路径）
     * @return 是否重载成功
     */
    public synchronized boolean reloadDataset(String datasetName) {
        if (!isValidDatasetName(datasetName)) {
            log.warn("热重载请求的数据集名称非法: {}", datasetName);
            return false;
        }

        String normalizedName = normalizeDatasetName(datasetName);
        log.info("正在执行单数据集独立热重载: {} (规范化名: {})", datasetName, normalizedName);

        // 1. 关闭并清理该数据集的独立 HikariCP 连接池
        HikariDataSource ds = dataSources.remove(normalizedName);
        if (ds != null) {
            ds.close();
            log.info("已释放数据集 '{}' 的连接池资源", normalizedName);
        }

        // 2. 清理元数据、访问时间戳、坐标系规范与表结构缓存
        lastAccessTimes.remove(normalizedName);
        datasetInfoCache.remove(normalizedName);
        datasetSchemes.remove(normalizedName);
        lockedSchemes.remove(normalizedName);
        datasetTableSchemas.remove(normalizedName);

        // 3. 清理 Spring Cache 中的 metadata 缓存
        Cache metaCache = cacheManager.getCache("metadata");
        if (metaCache != null) {
            metaCache.evict(normalizedName);
        }

        // 4. 精准驱逐 Caffeine 瓦片缓存中以当前数据集为前缀的瓦片（如 "beijing:*"）
        Cache tileCache = cacheManager.getCache("tiles");
        if (tileCache != null && tileCache.getNativeCache() instanceof com.github.benmanes.caffeine.cache.Cache) {
            com.github.benmanes.caffeine.cache.Cache<?, ?> nativeCache = (com.github.benmanes.caffeine.cache.Cache<?, ?>) tileCache.getNativeCache();
            String prefix = normalizedName + ":";
            long beforeCount = nativeCache.estimatedSize();
            nativeCache.asMap().keySet().removeIf(k -> (k instanceof String) && ((String) k).startsWith(prefix));
            long evicted = beforeCount - nativeCache.estimatedSize();
            log.info("已精准驱逐数据集 '{}' 的内存瓦片缓存（清除约 {} 条缓存项），其余数据集缓存完整保留", normalizedName, evicted);
        }

        // 5. 若全局启用了瓦片预热，异步触发该重载数据集的低层级批量预热
        if (properties.getWarmup().isEnabled()) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                warmupDatasetBatch(normalizedName, properties.getWarmup().getMaxZoom());
            });
        }

        return true;
    }

    /**
     * 热重载所有数据集：释放全部连接池、清空内存缓存，并在后续请求时按需重新加载
     */
    public synchronized void reloadDatasets() {
        log.info("正在执行 MBTiles 数据集全局热重载...");
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
        datasetSchemes.clear();
        lockedSchemes.clear();
        datasetTableSchemas.clear();
    }
}
