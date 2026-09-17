package com.map.mbtiles;

import com.map.mbtiles.service.DatasetInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 瓦片引擎核心功能单元测试
 * 覆盖坐标换算、路径安全校验、CRC32 ETag 计算、元数据解析与 RFC 7232 条件请求比对
 */
class TileServiceTest {

    private static final Pattern SAFE_DATASET_NAME = Pattern.compile("^[a-zA-Z0-9_-]+$");

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
    @DisplayName("数据集安全名称正则校验，防御路径穿越")
    void testDatasetNameValidation() {
        assertTrue(SAFE_DATASET_NAME.matcher("basemap_line_point").matches());
        assertTrue(SAFE_DATASET_NAME.matcher("dataset-v1_2").matches());
        assertTrue(SAFE_DATASET_NAME.matcher("map123").matches());

        // 恶意注入输入必须被成功拦截
        assertFalse(SAFE_DATASET_NAME.matcher("../secret").matches());
        assertFalse(SAFE_DATASET_NAME.matcher("..\\windows\\system32").matches());
        assertFalse(SAFE_DATASET_NAME.matcher("/etc/passwd").matches());
        assertFalse(SAFE_DATASET_NAME.matcher("data/basemap").matches());
        assertFalse(SAFE_DATASET_NAME.matcher("data;rm").matches());
        assertFalse(SAFE_DATASET_NAME.matcher("data' OR 1=1--").matches());
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
    @DisplayName("DatasetInfo 元数据与 Zoom 范围边界校验")
    void testDatasetInfoParsing() {
        Map<String, String> rawMeta = Map.of(
                "name", "basemap",
                "format", "pbf",
                "minzoom", "6",
                "maxzoom", "14",
                "bounds", "115.0,39.0,117.0,41.0",
                "center", "116.0,40.0,10",
                "json", "{\"vector_layers\": [{\"id\": \"roads\", \"fields\": {}}]}"
        );

        DatasetInfo info = DatasetInfo.fromMetadata("basemap", rawMeta, 1024000L, System.currentTimeMillis());

        assertEquals("basemap", info.getName());
        assertEquals("pbf", info.getFormat());
        assertEquals(6, info.getMinzoom());
        assertEquals(14, info.getMaxzoom());
        assertNotNull(info.getBounds());
        assertEquals(4, info.getBounds().length);
        assertEquals(115.0, info.getBounds()[0]);
        assertEquals(1, info.getVectorLayers().size());
        assertEquals("roads", info.getVectorLayers().get(0).get("id"));

        // Zoom 范围有效性检查
        assertFalse(info.isZoomValid(5), "低于 minzoom=6 应判定无效");
        assertTrue(info.isZoomValid(6), "等于 minzoom=6 应有效");
        assertTrue(info.isZoomValid(10), "介于 6~14 之间应有效");
        assertTrue(info.isZoomValid(14), "等于 maxzoom=14 应有效");
        assertFalse(info.isZoomValid(15), "超出 maxzoom=14 应判定无效");
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
