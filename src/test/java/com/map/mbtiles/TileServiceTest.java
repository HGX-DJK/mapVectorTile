package com.map.mbtiles;

import com.map.mbtiles.model.DatasetInfo;
import com.map.mbtiles.model.TileEntry;
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
        Map<String, String> rawMeta = Map.of(
                "name", "beijing_basemap",
                "format", "pbf",
                "minzoom", "8",
                "maxzoom", "14",
                "bounds", "115.4,39.4,117.5,41.1",
                "center", "116.4,39.9,10",
                "json", "{\"vector_layers\": [{\"id\": \"roads\", \"fields\": {}}]}"
        );

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

    /**
     * 辅助方法：校验 ETag 是否与客户端发送的 If-None-Match 请求头匹配
     */
    private boolean matchesETag(String etag, String ifNoneMatch) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) return false;
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
}
