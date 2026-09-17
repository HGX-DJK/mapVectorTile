package com.map.mbtiles.controller;

import com.map.mbtiles.config.MbtilesProperties;
import com.map.mbtiles.model.DatasetInfo;
import com.map.mbtiles.model.TileEntry;
import com.map.mbtiles.service.MbtilesService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 矢量瓦片 REST 控制器
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
     * 数据集热重载端点 — 动态重新载入磁盘上的 MBTiles / DB 文件并重置连接池与缓存
     */
    @PostMapping(value = "/reload", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> reloadDatasets() {
        mbtilesService.reloadDatasets();
        return ResponseEntity.ok(Map.of("status", "success", "message", "MBTiles / DB 数据集与缓存已全部热重载"));
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
     * 支持平铺名称与子目录路径（例如 /tiles/admin/beijing/tilejson.json）
     */
    @GetMapping(value = {
            "/{datasetName}/tilejson.json",
            "/**/tilejson.json"
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
        tileJson.put("tiles", List.of(tileUrl));
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
            "/**/metadata.json"
    }, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getMetadata(
            @PathVariable(required = false) String datasetName,
            HttpServletRequest request) {

        return getTileJson(datasetName, request);
    }

    /**
     * 获取指定坐标的矢量瓦片
     * 原生支持 .pbf、.mvt 及无后缀路由，并自适应匹配子目录（如 /tiles/vector/roads/10/857/418.pbf）
     */
    @GetMapping(value = {
            "/{datasetName}/{z}/{x}/{y}.pbf",
            "/{datasetName}/{z}/{x}/{y}.mvt",
            "/{datasetName}/{z}/{x}/{y}",
            "/**/{z}/{x}/{y}.pbf",
            "/**/{z}/{x}/{y}.mvt",
            "/**/{z}/{x}/{y}"
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

        // 2. 坐标数值基础范围校验
        if (z < 0 || z > 22 || x < 0 || y < 0) {
            return ResponseEntity.badRequest().build();
        }
        int maxCoord = 1 << z;
        if (x >= maxCoord || y >= maxCoord) {
            return ResponseEntity.badRequest().build();
        }

        // 3. 数据集元数据校验
        DatasetInfo info = mbtilesService.getDatasetInfo(resolvedName);
        if (info == null) {
            return ResponseEntity.notFound().build();
        }

        // 4. 双重前置短路剪枝：
        //    (a) 层级范围短路：z 不在 [minzoom, maxzoom]
        //    (b) 空间拓扑短路：(x, y) 完全在数据集 BBox 外包矩形之外
        //    超出范围直接响应 204，零缓存与数据库损耗
        if (!info.isZoomValid(z) || !info.isTileWithinBounds(z, x, y)) {
            return ResponseEntity.noContent()
                    .header(HttpHeaders.CACHE_CONTROL, getCacheControlHeader())
                    .build();
        }

        // 5. 查询瓦片（命中内存缓存或 SQLite，查无数据自动返回 TileEntry.EMPTY 单例）
        TileEntry tile = mbtilesService.getTile(resolvedName, z, x, y);

        if (tile == null || tile.isEmpty()) {
            return ResponseEntity.noContent()
                    .header(HttpHeaders.CACHE_CONTROL, getCacheControlHeader())
                    .build();
        }

        String etag = tile.etag();

        // 6. 遵循 RFC 7232 标准进行条件请求比对（支持 W/ 弱 ETag 与多 ETag 列表）
        if (matchesETag(etag, ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, etag)
                    .header(HttpHeaders.CACHE_CONTROL, getCacheControlHeader())
                    .build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "application/x-protobuf");
        headers.set(HttpHeaders.CACHE_CONTROL, getCacheControlHeader());
        headers.set(HttpHeaders.ETAG, etag);
        headers.set(HttpHeaders.VARY, "Accept-Encoding");

        if (tile.gzipped()) {
            headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        }

        return new ResponseEntity<>(tile.data(), headers, HttpStatus.OK);
    }

    /**
     * 辅助方法：从瓦片请求 URI 中智能提取包含子目录的数据集全名
     */
    private String resolveDatasetNameForTile(HttpServletRequest request, String pathVar, int z, int x, int y) {
        if (pathVar != null && !pathVar.isBlank() && !pathVar.contains("/")) {
            return mbtilesService.normalizeDatasetName(pathVar);
        }

        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }

        // 去掉前缀 /tiles/
        if (uri.startsWith("/tiles/")) {
            uri = uri.substring(7);
        }

        // 剥离尾部的 /{z}/{x}/{y}(.pbf/.mvt)
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
        if (pathVar != null && !pathVar.isBlank() && !pathVar.contains("/")) {
            return mbtilesService.normalizeDatasetName(pathVar);
        }

        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isBlank() && uri.startsWith(contextPath)) {
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
        if (scheme == null || scheme.isBlank()) {
            scheme = request.getScheme();
        }

        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isBlank()) {
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
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
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
