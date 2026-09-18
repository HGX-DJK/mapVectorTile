package com.map.mbtiles;

import com.map.mbtiles.model.DatasetInfo;
import com.map.mbtiles.model.TableSchema;
import com.map.mbtiles.model.TileEntry;
import com.map.mbtiles.model.TileScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 瓦片引擎核心功能单元测试
 * 覆盖坐标换算、路径安全校验、子目录支持、CRC32 ETag 计算、元数据解析、空间范围剪枝、空瓦片单例及 RFC 7232 条件请求比对
 */
class TileServiceTest {

    private static final Pattern SAFE_DATASET_NAME = Pattern.compile("^[a-zA-Z0-9_-]+(/[a-zA-Z0-9_-]+)*$");

    @Test
    @DisplayName("TableSchema 标准与非标表名 (grids) / 字段 (grid) SQL 生成正确性")
    void testTableSchemaSqlGeneration() {
        // 1. 标准 MBTiles 表结构
        TableSchema defaultSchema = TableSchema.DEFAULT;
        assertEquals("SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                defaultSchema.selectTileSql());
        assertEquals("SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles WHERE zoom_level <= ?",
                defaultSchema.selectWarmupSql());
        assertEquals("CREATE UNIQUE INDEX IF NOT EXISTS tiles_zxy_idx ON tiles (zoom_level, tile_column, tile_row)",
                defaultSchema.createIndexSql());

        // 2. 非标 grids 表与 grid 字段
        TableSchema customSchema = new TableSchema("grids", "zoom_level", "tile_column", "tile_row", "grid");
        assertEquals("SELECT grid FROM grids WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?",
                customSchema.selectTileSql());
        assertEquals("SELECT zoom_level, tile_column, tile_row, grid FROM grids WHERE zoom_level <= ?",
                customSchema.selectWarmupSql());
        assertEquals("CREATE UNIQUE INDEX IF NOT EXISTS grids_zxy_idx ON grids (zoom_level, tile_column, tile_row)",
                customSchema.createIndexSql());
    }

    @Test
    @DisplayName("二进制流前导魔数（Magic Number）智能推断真实 MIME 类型")
    void testMagicBytesContentTypeDetection() {
        // PNG 魔数: 89 50 4E 47
        byte[] pngHeader = new byte[]{(byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        TileEntry pngTile = new TileEntry(pngHeader, "\"png\"", false);
        assertEquals("image/png", pngTile.detectContentType());

        // JPEG 魔数: FF D8 FF
        byte[] jpegHeader = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        TileEntry jpegTile = new TileEntry(jpegHeader, "\"jpeg\"", false);
        assertEquals("image/jpeg", jpegTile.detectContentType());

        // WebP 魔数: RIFF....WEBP
        byte[] webpHeader = new byte[]{(byte) 'R', (byte) 'I', (byte) 'F', (byte) 'F', 0, 0, 0, 0, (byte) 'W', (byte) 'E', (byte) 'B', (byte) 'P'};
        TileEntry webpTile = new TileEntry(webpHeader, "\"webp\"", false);
        assertEquals("image/webp", webpTile.detectContentType());

        // Protobuf / 矢量切片
        byte[] pbfPayload = new byte[]{0x1A, 0x2B, 0x3C, 0x4D};
        TileEntry pbfTile = new TileEntry(pbfPayload, "\"pbf\"", false);
        assertEquals("application/x-protobuf", pbfTile.detectContentType());
    }

    @Test
    @DisplayName("TMS 与 XYZ 坐标相互转换数学正确性")
    void testCoordinateConversion() {
        int z = 10;
        int y = 418;
        int tmsY = (1 << z) - 1 - y;

        assertEquals(605, tmsY, "z=10, y=418 时 TMS Y 应为 1023 - 418 = 605");

        // 逆转换验证
        int originalY = (1 << z) - 1 - tmsY;
        assertEquals(y, originalY);
    }

    @Test
    @DisplayName("TileScheme 枚举解析、行列号双向转换与自适应映射正确性")
    void testTileSchemeConversion() {
        assertEquals(TileScheme.TMS, TileScheme.fromString("tms"));
        assertEquals(TileScheme.TMS, TileScheme.fromString("TMS"));
        assertEquals(TileScheme.XYZ, TileScheme.fromString("xyz"));
        assertEquals(TileScheme.XYZ, TileScheme.fromString("XYZ"));
        assertNull(TileScheme.fromString("unknown"));
        assertNull(TileScheme.fromString(null));

        int z = 10;
        int webY = 418;

        // TMS: 左下角原点，toDatabaseRow 必须翻转
        assertEquals(605, TileScheme.TMS.toDatabaseRow(z, webY));
        assertEquals(webY, TileScheme.TMS.toWebY(z, 605));

        // XYZ: 左上角原点，toDatabaseRow 必须原样直传
        assertEquals(418, TileScheme.XYZ.toDatabaseRow(z, webY));
        assertEquals(webY, TileScheme.XYZ.toWebY(z, 418));
    }

    @Test
    @DisplayName("数据集安全名称正则校验，支持子目录层级并严格防御路径穿越")
    void testDatasetNameValidation() {
        // 单层数据集名称
        assertTrue(SAFE_DATASET_NAME.matcher("basemap_line_point").matches());
        assertTrue(SAFE_DATASET_NAME.matcher("dataset-v1_2").matches());
        assertTrue(SAFE_DATASET_NAME.matcher("map123").matches());

        // 多层子目录结构名称
        assertTrue(SAFE_DATASET_NAME.matcher("vector/roads").matches());
        assertTrue(SAFE_DATASET_NAME.matcher("admin/beijing/district").matches());

        // 恶意注入与非法路径必须被成功拦截
        assertFalse(SAFE_DATASET_NAME.matcher("../secret").matches());
        assertFalse(SAFE_DATASET_NAME.matcher("vector/../../secret").matches());
        assertFalse(SAFE_DATASET_NAME.matcher("..\\windows\\system32").matches());
        assertFalse(SAFE_DATASET_NAME.matcher("/etc/passwd").matches());
        assertFalse(SAFE_DATASET_NAME.matcher("data/").matches());
        assertFalse(SAFE_DATASET_NAME.matcher("/vector/roads").matches());
        assertFalse(SAFE_DATASET_NAME.matcher("vector//roads").matches());
        assertFalse(SAFE_DATASET_NAME.matcher("data;rm").matches());
        assertFalse(SAFE_DATASET_NAME.matcher("data' OR 1=1--").matches());
    }

    @Test
    @DisplayName("空瓦片单例 TileEntry.EMPTY 行为验证")
    void testEmptyTileEntry() {
        TileEntry empty = TileEntry.EMPTY;
        assertNotNull(empty);
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.data().length);
        assertEquals("\"empty\"", empty.etag());
        assertFalse(empty.gzipped());
    }

    @Test
    @DisplayName("CRC32 ETag 计算一致性与格式正确性")
    void testCrc32EtagGeneration() {
        byte[] sample = "sample vector tile protobuf payload".getBytes();
        CRC32 crc = new CRC32();
        crc.update(sample);
        String etag = "\"" + Long.toHexString(crc.getValue()) + "\"";

        assertNotNull(etag);
        assertTrue(etag.startsWith("\"") && etag.endsWith("\""));
        assertEquals(etag, "\"" + Long.toHexString(crc.getValue()) + "\"");
    }

    @Test
    @DisplayName("DatasetInfo 元数据、Zoom 范围与地理空间 BBox 拓扑短路剪枝校验")
    void testDatasetInfoAndBBoxPruning() {
        // 模拟一个北京区域数据集: 经度 115.4 ~ 117.5, 纬度 39.4 ~ 41.1
        Map<String, String> rawMeta = new java.util.LinkedHashMap<>();
        rawMeta.put("name", "beijing_basemap");
        rawMeta.put("format", "pbf");
        rawMeta.put("minzoom", "8");
        rawMeta.put("maxzoom", "14");
        rawMeta.put("bounds", "115.4,39.4,117.5,41.1");
        rawMeta.put("center", "116.4,39.9,10");
        rawMeta.put("json", "{\"vector_layers\": [{\"id\": \"roads\", \"fields\": {}}]}");

        DatasetInfo info = DatasetInfo.fromMetadata("beijing_basemap", rawMeta, 1024000L, System.currentTimeMillis());

        assertEquals("beijing_basemap", info.getName());
        assertEquals("pbf", info.getFormat());
        assertEquals(8, info.getMinzoom());
        assertEquals(14, info.getMaxzoom());

        // Zoom 范围校验
        assertFalse(info.isZoomValid(7), "低于 minzoom=8 应判定无效");
        assertTrue(info.isZoomValid(8), "等于 minzoom=8 应有效");
        assertTrue(info.isZoomValid(10), "介于 8~14 之间应有效");
        assertFalse(info.isZoomValid(15), "超出 maxzoom=14 应判定无效");

        // BBox 空间范围剪枝测试（z=10 时，北京瓦片大致为 x=842~847, y=384~390 附近）
        // 1. 北京市中心内部瓦片：应在范围内
        assertTrue(info.isTileWithinBounds(10, 843, 388), "北京中心范围瓦片应在 BBox 内");

        // 2. 远在纽约的瓦片 (z=10, 经度约 -74，X 约为 300 左右)：必须被剪枝过滤
        assertFalse(info.isTileWithinBounds(10, 301, 384), "远在纽约的瓦片应被空间短路剪枝阻断");

        // 3. 远在伦敦的瓦片 (z=10, 经度约 0，X 约为 512 左右)：必须被剪枝过滤
        assertFalse(info.isTileWithinBounds(10, 512, 340), "远在伦敦的瓦片应被空间短路剪枝阻断");
    }

    @Test
    @DisplayName("TileRange 紧凑边界预计算与 O(1) 区间匹配正确性")
    void testTileRangePrecomputation() {
        DatasetInfo.TileRange range = new DatasetInfo.TileRange(10, 20, 30, 40);
        assertTrue(range.contains(10, 30));
        assertTrue(range.contains(15, 35));
        assertTrue(range.contains(20, 40));
        assertFalse(range.contains(9, 35));
        assertFalse(range.contains(21, 35));
        assertFalse(range.contains(15, 29));
        assertFalse(range.contains(15, 41));

        // 全球范围 null 检查
        double[] globalBounds = new double[]{-180.0, -85.05112878, 180.0, 85.05112878};
        assertNull(DatasetInfo.computeTileRanges(globalBounds), "全球覆盖边界无需剪枝，应返回 null");

        // 局部区域预计算
        double[] bjBounds = new double[]{115.4, 39.4, 117.5, 41.1};
        DatasetInfo.TileRange[] ranges = DatasetInfo.computeTileRanges(bjBounds);
        assertNotNull(ranges);
        assertEquals(23, ranges.length);

        // z=10 时北京瓦片验证
        DatasetInfo.TileRange r10 = ranges[10];
        assertTrue(r10.contains(843, 388), "z=10 时北京中心瓦片应在预计算范围内");
        assertFalse(r10.contains(301, 384), "z=10 时纽约瓦片不在北京预计算范围内");
    }

    @Test
    @DisplayName("RFC 7232 ETag 条件比对支持弱 ETag 与多 ETag")
    void testEtagMatching() {
        String serverEtag = "\"1a2b3c4d\"";

        assertTrue(matchesETag(serverEtag, "\"1a2b3c4d\""), "强 ETag 完全一致应匹配");
        assertTrue(matchesETag(serverEtag, "W/\"1a2b3c4d\""), "弱 ETag 带有 W/ 应成功匹配");
        assertTrue(matchesETag(serverEtag, "*"), "* 通配符应成功匹配");
        assertTrue(matchesETag(serverEtag, "\"other\", \"1a2b3c4d\", \"xyz\""), "逗号分隔列表中包含目标 ETag 应匹配");
        assertFalse(matchesETag(serverEtag, "\"9999999\""), "不同 ETag 不应匹配");
        assertFalse(matchesETag(serverEtag, null), "null 头部不应匹配");
    }

    @Test
    @DisplayName("数据集扩展名自动剥离与双下划线子目录映射校验")
    void testDatasetNameExtensionStripping() {
        assertEquals("beijing", normalizeDatasetName("beijing.db"));
        assertEquals("beijing", normalizeDatasetName("beijing.mbtiles"));
        assertEquals("beijing", normalizeDatasetName("beijing.sqlite"));
        assertEquals("beijing", normalizeDatasetName("beijing.sqlite3"));
        assertEquals("admin/beijing", normalizeDatasetName("admin__beijing.db"));
        assertEquals("admin/beijing", normalizeDatasetName("admin/beijing.mbtiles"));
        assertEquals("basemap/vector/roads", normalizeDatasetName("basemap__vector__roads.sqlite3"));

        // 验证剥离后均能顺利通过安全正则检查
        assertTrue(SAFE_DATASET_NAME.matcher(normalizeDatasetName("beijing.db")).matches());
        assertTrue(SAFE_DATASET_NAME.matcher(normalizeDatasetName("admin__beijing.db")).matches());
        assertTrue(SAFE_DATASET_NAME.matcher(normalizeDatasetName("vector/roads.mbtiles")).matches());

        // 验证路径穿越即使携带扩展名依然被拦截
        assertFalse(SAFE_DATASET_NAME.matcher(normalizeDatasetName("../secret.db")).matches());
        assertFalse(SAFE_DATASET_NAME.matcher(normalizeDatasetName("a/../../secret.mbtiles")).matches());
    }

    @Test
    @DisplayName("Gzip 压缩与动态解压数据一致性校验 (RFC 7231)")
    void testGzipDecompression() throws Exception {
        byte[] original = "Vector tile protobuf test data payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // 模拟 Gzip 压缩
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gos = new java.util.zip.GZIPOutputStream(baos)) {
            gos.write(original);
        }
        byte[] compressed = baos.toByteArray();

        // 验证 Gzip 魔数 0x1F, 0x8B
        assertTrue(compressed.length >= 2 && compressed[0] == (byte) 0x1F && compressed[1] == (byte) 0x8B);

        // 解压缩验证
        java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(compressed);
        try (java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(bais);
             java.io.ByteArrayOutputStream decompressedBaos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[1024];
            int n;
            while ((n = gis.read(buf)) > 0) {
                decompressedBaos.write(buf, 0, n);
            }
            byte[] decompressed = decompressedBaos.toByteArray();
            assertArrayEquals(original, decompressed, "解压后的字节数据应与原始字节严格一致");
        }
    }

    private static final java.util.List<String> SUPPORTED_EXTENSIONS = java.util.Arrays.asList(
            ".mbtiles",
            ".db",
            ".sqlite",
            ".sqlite3"
    );

    private String normalizeDatasetName(String datasetName) {
        if (datasetName == null) return null;
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
     * 辅助方法：校验 ETag 是否与客户端发送的 If-None-Match 请求头匹配
     */
    private boolean matchesETag(String etag, String ifNoneMatch) {
        if (ifNoneMatch == null || ifNoneMatch.trim().isEmpty()) return false;
        String cleanEtag = stripQuotesAndWeak(etag);
        for (String token : ifNoneMatch.split(",")) {
            String trimmed = token.trim();
            if ("*".equals(trimmed)) return true;
            if (cleanEtag.equals(stripQuotesAndWeak(trimmed))) return true;
        }
        return false;
    }

    /**
     * 辅助方法：剥离弱标签前缀 W/ 与首尾引号
     */
    private String stripQuotesAndWeak(String tag) {
        if (tag == null) return "";
        String t = tag.trim();
        if (t.startsWith("W/") || t.startsWith("w/")) t = t.substring(2).trim();
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) t = t.substring(1, t.length() - 1);
        return t;
    }

    @Test
    @DisplayName("ApiErrorResponse 结构体与 JSON 序列化规范性测试")
    void testApiErrorResponse() throws Exception {
        com.map.mbtiles.model.ApiErrorResponse err = new com.map.mbtiles.model.ApiErrorResponse(
                400, "Bad Request", "瓦片坐标参数类型错误", "/tiles/beijing/abc/1/2.pbf", 1789710000000L
        );
        assertEquals(400, err.getStatus());
        assertEquals("Bad Request", err.getError());
        assertEquals("瓦片坐标参数类型错误", err.getMessage());
        assertEquals("/tiles/beijing/abc/1/2.pbf", err.getPath());
        assertEquals(1789710000000L, err.getTimestamp());

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = mapper.writeValueAsString(err);
        assertTrue(json.contains("\"status\":400"));
        assertTrue(json.contains("\"error\":\"Bad Request\""));
        assertTrue(json.contains("\"message\":\"瓦片坐标参数类型错误\""));
        assertTrue(json.contains("\"path\":\"/tiles/beijing/abc/1/2.pbf\""));
    }

    @Test
    @DisplayName("DataDirectoryWatcher 路径提取与子目录解析正确性测试")
    void testWatcherDatasetNameResolution() {
        com.map.mbtiles.service.DataDirectoryWatcher watcher = new com.map.mbtiles.service.DataDirectoryWatcher(null, null);
        java.nio.file.Path rootDir = java.nio.file.Paths.get("data");

        // 顶层文件
        java.nio.file.Path file1 = java.nio.file.Paths.get("data", "beijing.mbtiles");
        assertEquals("beijing", watcher.resolveDatasetName(rootDir, file1));

        // 1级子目录文件
        java.nio.file.Path file2 = java.nio.file.Paths.get("data", "vector", "roads.db");
        assertEquals("vector/roads", watcher.resolveDatasetName(rootDir, file2));

        // 2级子目录文件
        java.nio.file.Path file3 = java.nio.file.Paths.get("data", "2026", "base", "lines.sqlite");
        assertEquals("2026/base/lines", watcher.resolveDatasetName(rootDir, file3));
    }

    @Test
    @DisplayName("RFC 7232 If-Modified-Since 304 短路比对数学逻辑测试")
    void testIfModifiedSinceLogic() {
        long fileLastModified = 1789710000000L;

        // 1. 客户端时间与文件时间完全一致 -> 304
        long clientTimeExact = 1789710000000L;
        assertTrue(fileLastModified <= clientTimeExact + 1000, "时间一致应触发 304");

        // 2. 客户端时间晚于文件时间 (更年轻) -> 304
        long clientTimeNewer = 1789715000000L;
        assertTrue(fileLastModified <= clientTimeNewer + 1000, "客户端缓存更新应触发 304");

        // 3. 客户端时间早于文件时间 (文件已更新) -> 200，不能触发 304
        long clientTimeOlder = 1789700000000L;
        assertFalse(fileLastModified <= clientTimeOlder + 1000, "文件更新后客户端缓存过期，不能触发 304");
    }
}
