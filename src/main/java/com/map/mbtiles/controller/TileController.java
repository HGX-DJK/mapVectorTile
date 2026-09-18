package com.map.mbtiles.controller;

import com.map.mbtiles.config.MbtilesProperties;
import com.map.mbtiles.model.DatasetInfo;
import com.map.mbtiles.model.TileEntry;
import com.map.mbtiles.service.MbtilesService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 矢量瓦片 REST 控制器（兼容 Java 8）
 * 提供瓦片二进制流响应（支持 .pbf / .mvt 双后缀及子目录层级）、TileJSON 3.0 标准规范端点、
 * 数据集目录、空间范围（BBox）拓扑剪枝、缓存指标监控与数据集热重载
 */
@RestController
@RequestMapping("/tiles")
public class TileController {

    private final MbtilesService mbtilesService;
    private final MbtilesProperties properties;

    public TileController(MbtilesService mbtilesService, MbtilesProperties properties) {
        this.mbtilesService = mbtilesService;
        this.properties = properties;
    }

    private String getCacheControlHeader() {
        return properties.getCacheControl().toHeaderValue();
    }

    /**
     * 空白切片/越界切片专用防污染 Cache-Control 头
     * 严禁将 204 No Content 标记为 7 天 immutable 强缓存，杜绝客户端浏览器 Disk Cache 锁死白块
     */
    private static final String NO_CONTENT_CACHE_CONTROL = "no-cache, no-store, must-revalidate";

    /**
     * 服务健康检查端点 — 用于容器探针与监控服务存活状态
     */
    @GetMapping(value = "/health", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("UP | datasources=" + mbtilesService.getDataSourceCount());
    }

    /**
     * 实时缓存监控端点 — 查看 Caffeine 内存缓存命中率、当前缓存容量与驱逐次数
     */
    @GetMapping(value = "/cache-stats", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> cacheStats() {
        return ResponseEntity.ok(mbtilesService.getCacheStats());
    }

    /**
     * 数据集热重载端点：
     * 1. 支持指定单个数据集细粒度重载：POST /tiles/reload?dataset=beijing
     *    仅释放该数据集的连接池并按前缀精准驱逐其瓦片缓存，保留其他数据集的高命中率缓存（防雪崩）
     * 2. 支持全量重载：POST /tiles/reload
     */
    @PostMapping(value = "/reload", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> reloadDatasets(
            @RequestParam(value = "dataset", required = false) String dataset) {

        if (dataset != null && !dataset.trim().isEmpty()) {
            if (!mbtilesService.isValidDatasetName(dataset)) {
                Map<String, String> err = new LinkedHashMap<>();
                err.put("status", "error");
                err.put("message", "指定重载的数据集名称非法: " + dataset);
                return ResponseEntity.badRequest().body(err);
            }
            boolean success = mbtilesService.reloadDataset(dataset);
            if (success) {
                Map<String, String> res = new LinkedHashMap<>();
                res.put("status", "success");
                res.put("dataset", mbtilesService.normalizeDatasetName(dataset));
                res.put("message", "数据集 '" + dataset + "' 已独立热重载完成（其余数据集缓存完整保留）");
                return ResponseEntity.ok(res);
            } else {
                Map<String, String> err = new LinkedHashMap<>();
                err.put("status", "error");
                err.put("message", "数据集 '" + dataset + "' 热重载失败");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(err);
            }
        }

        mbtilesService.reloadDatasets();
        Map<String, String> allRes = new LinkedHashMap<>();
        allRes.put("status", "success");
        allRes.put("message", "所有 MBTiles / DB 数据集与缓存已全部全局热重载");
        return ResponseEntity.ok(allRes);
    }

    /**
     * 单数据集路径式独立热重载端点：POST /tiles/{datasetName}/reload
     * 兼容 1~3 级子目录（如 /tiles/vector/roads/reload）与双下划线别名
     */
    @PostMapping(value = {
            "/{datasetName}/reload",
            "/{dir1}/{datasetName}/reload",
            "/{dir1}/{dir2}/{datasetName}/reload"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> reloadSingleDataset(
            @PathVariable(required = false) String datasetName,
            HttpServletRequest request) {

        String resolvedName = resolveDatasetNameFromRequest(request, datasetName, "/reload");
        return reloadDatasets(resolvedName);
    }

    /**
     * 数据集目录发现接口 — 列出 data 目录下所有可用数据集（包含子目录）及其元数据概览
     */
    @GetMapping(value = {"", "/", "/datasets"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<DatasetInfo>> listDatasets() {
        List<DatasetInfo> datasets = mbtilesService.listDatasets();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=60")
                .body(datasets);
    }

    /**
     * 标准 TileJSON 3.0 规范端点
     * 兼容 Spring MVC，支持 1~3 级子目录及双下划线别名
     */
    @GetMapping(value = {
            "/{datasetName}/tilejson.json",
            "/{dir1}/{datasetName}/tilejson.json",
            "/{dir1}/{dir2}/{datasetName}/tilejson.json"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getTileJson(
            @PathVariable(required = false) String datasetName,
            HttpServletRequest request) {

        String resolvedName = resolveDatasetNameFromRequest(request, datasetName, "/tilejson.json");
        if (!mbtilesService.isValidDatasetName(resolvedName)) {
            return ResponseEntity.badRequest().build();
        }

        DatasetInfo info = mbtilesService.getDatasetInfo(resolvedName);
        if (info == null) {
            return ResponseEntity.notFound().build();
        }

        // 动态根据客户端请求上下文组装瓦片模板 URL
        String baseUrl = resolveBaseUrl(request);
        String tileUrl = baseUrl + "/tiles/" + resolvedName + "/{z}/{x}/{y}.pbf";

        Map<String, Object> tileJson = new LinkedHashMap<>();
        tileJson.put("tilejson", "3.0.0");
        tileJson.put("name", info.getName());
        tileJson.put("description", info.getDescription());
        tileJson.put("version", info.getVersion());
        tileJson.put("attribution", info.getAttribution());
        tileJson.put("format", info.getFormat());
        tileJson.put("scheme", "xyz");
        tileJson.put("tiles", Collections.singletonList(tileUrl));
        tileJson.put("minzoom", info.getMinzoom());
        tileJson.put("maxzoom", info.getMaxzoom());
        tileJson.put("bounds", info.getBounds());
        tileJson.put("center", info.getCenter());
        if (info.getVectorLayers() != null && !info.getVectorLayers().isEmpty()) {
            tileJson.put("vector_layers", info.getVectorLayers());
        }

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, getCacheControlHeader())
                .body(tileJson);
    }

    /**
     * 向后兼容的元数据接口
     */
    @GetMapping(value = {
            "/{datasetName}/metadata.json",
            "/{dir1}/{datasetName}/metadata.json",
            "/{dir1}/{dir2}/{datasetName}/metadata.json"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getMetadata(
            @PathVariable(required = false) String datasetName,
            HttpServletRequest request) {

        return getTileJson(datasetName, request);
    }

    /**
     * 获取指定坐标的矢量或栅格瓦片
     * 支持 1~3 级子目录及 .pbf、.mvt、.png、.jpg、.webp 和无后缀全格式路由
     */
    @RequestMapping(method = {RequestMethod.GET, RequestMethod.HEAD}, value = {
            // 单层数据集路由 (支持矢量切片与栅格图片双模)
            "/{datasetName}/{z}/{x}/{y}.pbf",
            "/{datasetName}/{z}/{x}/{y}.mvt",
            "/{datasetName}/{z}/{x}/{y}.png",
            "/{datasetName}/{z}/{x}/{y}.jpg",
            "/{datasetName}/{z}/{x}/{y}.jpeg",
            "/{datasetName}/{z}/{x}/{y}.webp",
            "/{datasetName}/{z}/{x}/{y}",
            // 二层子目录路由
            "/{dir1}/{datasetName}/{z}/{x}/{y}.pbf",
            "/{dir1}/{datasetName}/{z}/{x}/{y}.mvt",
            "/{dir1}/{datasetName}/{z}/{x}/{y}.png",
            "/{dir1}/{datasetName}/{z}/{x}/{y}.jpg",
            "/{dir1}/{datasetName}/{z}/{x}/{y}.jpeg",
            "/{dir1}/{datasetName}/{z}/{x}/{y}.webp",
            "/{dir1}/{datasetName}/{z}/{x}/{y}",
            // 三层子目录路由
            "/{dir1}/{dir2}/{datasetName}/{z}/{x}/{y}.pbf",
            "/{dir1}/{dir2}/{datasetName}/{z}/{x}/{y}.mvt",
            "/{dir1}/{dir2}/{datasetName}/{z}/{x}/{y}.png",
            "/{dir1}/{dir2}/{datasetName}/{z}/{x}/{y}.jpg",
            "/{dir1}/{dir2}/{datasetName}/{z}/{x}/{y}.jpeg",
            "/{dir1}/{dir2}/{datasetName}/{z}/{x}/{y}.webp",
            "/{dir1}/{dir2}/{datasetName}/{z}/{x}/{y}"
    })
    public ResponseEntity<byte[]> getVectorTile(
            @PathVariable(required = false) String datasetName,
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch,
            HttpServletRequest request) {

        String resolvedName = resolveDatasetNameForTile(request, datasetName, z, x, y);

        // 1. 安全校验：防止路径遍历注入
        if (!mbtilesService.isValidDatasetName(resolvedName)) {
            return ResponseEntity.badRequest().build();
        }

        // 2. 坐标数值基础范围校验 (z 最大支持到 30，防止 1 << z 发生 32 位整型溢出)
        if (z < 0 || z > 30 || y < 0) {
            return ResponseEntity.badRequest().build();
        }
        int maxCoord = 1 << z;
        if (y >= maxCoord) {
            return ResponseEntity.badRequest().build();
        }
        // 经度水平循环归一化（支持世界地图水平无限滚动，杜绝跨 180° 经线时的越界 400 白块）
        int normalizedX = ((x % maxCoord) + maxCoord) % maxCoord;

        // 3. 数据集元数据校验
        DatasetInfo info = mbtilesService.getDatasetInfo(resolvedName);
        if (info == null) {
            return ResponseEntity.notFound().build();
        }

        // 4. 前置短路剪枝：
        //    (a) 层级范围短路：z 不在 [minzoom, maxzoom]（已通过 B-Tree 索引首列物理自校准保证绝对精准）
        if (!info.isZoomValid(z)) {
            return ResponseEntity.noContent()
                    .header(HttpHeaders.CACHE_CONTROL, NO_CONTENT_CACHE_CONTROL)
                    .build();
        }

        //    (b) 空间拓扑短路（可选）：仅在显式配置开启 bounds-filter-enabled 且 (normalizedX, y) 完全在 BBox 矩形外才拦截
        //        默认关闭：彻底杜绝元数据 bounds 标小导致的边缘有效切片被误杀返回 204
        if (properties.isBoundsFilterEnabled() && !info.isTileWithinBounds(z, normalizedX, y)) {
            return ResponseEntity.noContent()
                    .header(HttpHeaders.CACHE_CONTROL, NO_CONTENT_CACHE_CONTROL)
                    .build();
        }

        // 4.1 RFC 7232 日期条件请求秒级短路校验（If-Modified-Since 纳秒拦截）：
        //     若客户端携带时间戳且数据集文件未发生物理修改，瞬间响应 304，彻底跳过 Caffeine 缓存与 SQLite 检索
        long ifModifiedSince = -1;
        try {
            ifModifiedSince = request.getDateHeader(HttpHeaders.IF_MODIFIED_SINCE);
        } catch (IllegalArgumentException ignored) {
            // 忽略非标时间戳请求头
        }
        if (ifModifiedSince != -1 && info.getLastModified() <= ifModifiedSince + 1000) {
            HttpHeaders notModifiedHeaders = new HttpHeaders();
            notModifiedHeaders.set(HttpHeaders.CACHE_CONTROL, getCacheControlHeader());
            notModifiedHeaders.setDate(HttpHeaders.LAST_MODIFIED, info.getLastModified());
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .headers(notModifiedHeaders)
                    .build();
        }

        // 5. 查询瓦片（命中内存缓存或 SQLite，查无数据自动返回 TileEntry.EMPTY 单例）
        TileEntry tile = mbtilesService.getTile(resolvedName, z, normalizedX, y);

        if (tile == null || tile.isEmpty()) {
            return ResponseEntity.noContent()
                    .header(HttpHeaders.CACHE_CONTROL, NO_CONTENT_CACHE_CONTROL)
                    .build();
        }

        String etag = tile.etag();

        // 6. 遵循 RFC 7232 标准进行 ETag 条件请求比对（支持 W/ 弱 ETag 与多 ETag 列表）
        if (matchesETag(etag, ifNoneMatch)) {
            HttpHeaders notModifiedHeaders = new HttpHeaders();
            notModifiedHeaders.set(HttpHeaders.ETAG, etag);
            notModifiedHeaders.set(HttpHeaders.CACHE_CONTROL, getCacheControlHeader());
            notModifiedHeaders.setDate(HttpHeaders.LAST_MODIFIED, info.getLastModified());
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .headers(notModifiedHeaders)
                    .build();
        }

        // 7. HTTP HEAD 轻量探测模式：已确认瓦片存在，直接构造元数据头返回，不产生解压与 Payload 传输开销
        if ("HEAD".equalsIgnoreCase(request.getMethod())) {
            HttpHeaders headHeaders = new HttpHeaders();
            headHeaders.set(HttpHeaders.CONTENT_TYPE, tile.detectContentType());
            headHeaders.set(HttpHeaders.CACHE_CONTROL, getCacheControlHeader());
            headHeaders.set(HttpHeaders.ETAG, etag);
            headHeaders.setDate(HttpHeaders.LAST_MODIFIED, info.getLastModified());
            headHeaders.setContentLength(tile.data().length);
            return new ResponseEntity<>(new byte[0], headHeaders, HttpStatus.OK);
        }

        // 7. Gzip 内容协商（RFC 7231 / RFC 9110 规范）：
        //    如果瓦片经 gzip 压缩，但客户端未声明 Accept-Encoding: gzip（如仅 identity 或显式 gzip;q=0），
        //    服务端动态解压为未压缩原始流，确保兼容所有老旧地图渲染器与命令行工具。
        byte[] responseData = tile.data();
        boolean sendGzip = false;

        if (tile.gzipped()) {
            if (clientAcceptsGzip(request)) {
                sendGzip = true;
            } else {
                try {
                    responseData = decompressGzip(tile.data());
                } catch (IOException e) {
                    responseData = tile.data();
                    sendGzip = true;
                }
            }
        }

        HttpHeaders headers = new HttpHeaders();
        // 自动根据魔数识别下发 image/png, image/jpeg, image/webp 或 application/x-protobuf
        headers.set(HttpHeaders.CONTENT_TYPE, tile.detectContentType());
        headers.set(HttpHeaders.CACHE_CONTROL, getCacheControlHeader());
        headers.setDate(HttpHeaders.LAST_MODIFIED, info.getLastModified());
        // 解压后若以未压缩格式传输，以 W/ 弱 ETag 标示；压缩原样传输则使用强 ETag
        headers.set(HttpHeaders.ETAG, sendGzip ? etag : "W/" + etag);
        headers.set(HttpHeaders.VARY, "Accept-Encoding");

        if (sendGzip) {
            headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        }
        headers.setContentLength(responseData.length);

        return new ResponseEntity<>(responseData, headers, HttpStatus.OK);
    }

    /**
     * 判断客户端是否支持 gzip 压缩传输（遵循 RFC 7231 / RFC 9110 标准）
     * 检查 Accept-Encoding 请求头，若未提供或显式禁止 (gzip;q=0) 则返回 false
     */
    private boolean clientAcceptsGzip(HttpServletRequest request) {
        String acceptEncoding = request.getHeader(HttpHeaders.ACCEPT_ENCODING);
        if (acceptEncoding == null || acceptEncoding.trim().isEmpty()) {
            return false;
        }
        for (String encoding : acceptEncoding.split(",")) {
            String token = encoding.trim().toLowerCase();
            if (token.startsWith("gzip") || token.startsWith("*")) {
                if (token.contains(";q=0") || token.contains(";q=0.0") || token.contains(";q=0.00")) {
                    return false;
                }
                return true;
            }
        }
        return false;
    }

    /**
     * 对 Gzip 瓦片二进制流进行动态解压缩（客户端不支持 gzip 时的降级方案）
     */
    private byte[] decompressGzip(byte[] compressedData) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressedData);
             GZIPInputStream gis = new GZIPInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream(compressedData.length * 2)) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = gis.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        }
    }

    /**
     * 辅助方法：从瓦片请求 URI 中智能提取包含子目录的数据集全名
     */
    private String resolveDatasetNameForTile(HttpServletRequest request, String pathVar, int z, int x, int y) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.trim().isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }

        // 去掉前缀 /tiles/
        if (uri.startsWith("/tiles/")) {
            uri = uri.substring(7);
        }

        // 剥离尾部的 /{z}/{x}/{y}(.pbf/.mvt) 3 层坐标段
        int lastSlash = uri.lastIndexOf('/');
        if (lastSlash > 0) {
            int secondLast = uri.lastIndexOf('/', lastSlash - 1);
            if (secondLast > 0) {
                int thirdLast = uri.lastIndexOf('/', secondLast - 1);
                if (thirdLast > 0) {
                    return mbtilesService.normalizeDatasetName(uri.substring(0, thirdLast));
                }
            }
        }

        return mbtilesService.normalizeDatasetName(pathVar);
    }

    /**
     * 辅助方法：从元数据请求 URI 中提取数据集名称
     */
    private String resolveDatasetNameFromRequest(HttpServletRequest request, String pathVar, String suffix) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.trim().isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }

        if (uri.startsWith("/tiles/")) {
            uri = uri.substring(7);
        }

        int suffixIdx = uri.indexOf(suffix);
        if (suffixIdx > 0) {
            return mbtilesService.normalizeDatasetName(uri.substring(0, suffixIdx));
        }

        return mbtilesService.normalizeDatasetName(pathVar);
    }

    /**
     * 解析外部访问的 Base URL，自动识别反向代理头（X-Forwarded-Proto、X-Forwarded-Host）
     */
    private String resolveBaseUrl(HttpServletRequest request) {
        String scheme = request.getHeader("X-Forwarded-Proto");
        if (scheme == null || scheme.trim().isEmpty()) {
            scheme = request.getScheme();
        }

        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.trim().isEmpty()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.trim().isEmpty()) {
            host = request.getServerName();
            int port = request.getServerPort();
            if (port != 80 && port != 443 && port > 0) {
                host += ":" + port;
            }
        }

        String contextPath = request.getContextPath();
        return scheme + "://" + host + (contextPath != null ? contextPath : "");
    }

    /**
     * 校验 ETag 是否与客户端发送的 If-None-Match 请求头相匹配（RFC 7232 规范实现）
     */
    private boolean matchesETag(String etag, String ifNoneMatch) {
        if (ifNoneMatch == null || ifNoneMatch.trim().isEmpty()) {
            return false;
        }

        String cleanEtag = stripQuotesAndWeak(etag);
        for (String token : ifNoneMatch.split(",")) {
            String trimmed = token.trim();
            if ("*".equals(trimmed)) {
                return true;
            }
            if (cleanEtag.equals(stripQuotesAndWeak(trimmed))) {
                return true;
            }
        }
        return false;
    }

    private String stripQuotesAndWeak(String tag) {
        if (tag == null) {
            return "";
        }
        String t = tag.trim();
        if (t.startsWith("W/") || t.startsWith("w/")) {
            t = t.substring(2).trim();
        }
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            t = t.substring(1, t.length() - 1);
        }
        return t;
    }
}
