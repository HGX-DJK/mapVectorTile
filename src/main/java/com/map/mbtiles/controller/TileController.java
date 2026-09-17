package com.map.mbtiles.controller;

import com.map.mbtiles.config.MbtilesProperties;
import com.map.mbtiles.service.DatasetInfo;
import com.map.mbtiles.service.MbtilesService;
import com.map.mbtiles.service.TileEntry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/tiles")
@CrossOrigin(
        origins = "*",
        maxAge = 86400,
        exposedHeaders = {HttpHeaders.ETAG, HttpHeaders.CONTENT_LENGTH, HttpHeaders.CONTENT_ENCODING}
)
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
     * Health check endpoint — useful for monitoring and verifying the server is alive.
     */
    @GetMapping(value = "/health", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("UP | datasources=" + mbtilesService.getDataSourceCount());
    }

    /**
     * Dataset catalog endpoint — lists all available MBTiles datasets and their metadata summaries.
     */
    @GetMapping(value = {"", "/", "/datasets"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<DatasetInfo>> listDatasets() {
        List<DatasetInfo> datasets = mbtilesService.listDatasets();
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=60")
                .body(datasets);
    }

    /**
     * Standard TileJSON 3.0 specification endpoint.
     * Frontends (MapLibre GL JS, Mapbox GL JS, OpenLayers) can auto-configure sources using this URL.
     *
     * Example: GET /tiles/basemap_line_point/tilejson.json
     */
    @GetMapping(value = "/{datasetName}/tilejson.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getTileJson(
            @PathVariable String datasetName,
            HttpServletRequest request) {

        if (!mbtilesService.isValidDatasetName(datasetName)) {
            return ResponseEntity.badRequest().build();
        }

        DatasetInfo info = mbtilesService.getDatasetInfo(datasetName);
        if (info == null) {
            return ResponseEntity.notFound().build();
        }

        String baseUrl = resolveBaseUrl(request);
        String tileUrl = baseUrl + "/tiles/" + datasetName + "/{z}/{x}/{y}.pbf";

        Map<String, Object> tileJson = new LinkedHashMap<>();
        tileJson.put("tilejson", "3.0.0");
        tileJson.put("name", info.getName());
        tileJson.put("description", info.getDescription());
        tileJson.put("version", info.getVersion());
        tileJson.put("attribution", info.getAttribution());
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
     * Backward-compatible metadata endpoint.
     * Returns standard TileJSON attributes alongside raw MBTiles metadata.
     */
    @GetMapping(value = "/{datasetName}/metadata.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getMetadata(
            @PathVariable String datasetName,
            HttpServletRequest request) {

        return getTileJson(datasetName, request);
    }

    /**
     * Fetch vector tile in Mapbox Vector Tile (PBF) format.
     */
    @GetMapping("/{datasetName}/{z}/{x}/{y}.pbf")
    public ResponseEntity<byte[]> getVectorTile(
            @PathVariable String datasetName,
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        // 1. Safety validation: prevent path traversal attacks
        if (!mbtilesService.isValidDatasetName(datasetName)) {
            return ResponseEntity.badRequest().build();
        }

        // 2. Coordinate range validation
        if (z < 0 || z > 22 || x < 0 || y < 0) {
            return ResponseEntity.badRequest().build();
        }
        int maxCoord = 1 << z;
        if (x >= maxCoord || y >= maxCoord) {
            return ResponseEntity.badRequest().build();
        }

        // 3. Zoom range fast short-circuit from dataset metadata
        DatasetInfo info = mbtilesService.getDatasetInfo(datasetName);
        if (info == null) {
            return ResponseEntity.notFound().build();
        }
        if (!info.isZoomValid(z)) {
            return ResponseEntity.noContent()
                    .header(HttpHeaders.CACHE_CONTROL, getCacheControlHeader())
                    .build();
        }

        // 4. Retrieve tile (from Caffeine or SQLite)
        TileEntry tile = mbtilesService.getTile(datasetName, z, x, y);

        if (tile == null) {
            return ResponseEntity.noContent()
                    .header(HttpHeaders.CACHE_CONTROL, getCacheControlHeader())
                    .build();
        }

        String etag = tile.etag();

        // 5. RFC 7232 compliant conditional GET check (supports W/"..." and comma-separated tags)
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
     * Resolves the external base URL respecting reverse proxy headers (X-Forwarded-Proto, X-Forwarded-Host).
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
     * Checks if the given ETag matches the client's If-None-Match header according to RFC 7232.
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
