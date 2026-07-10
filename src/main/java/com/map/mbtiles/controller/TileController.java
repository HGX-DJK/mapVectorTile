package com.map.mbtiles.controller;

import com.map.mbtiles.service.MbtilesService;
import com.map.mbtiles.service.TileEntry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.http.MediaType;

@RestController
@RequestMapping("/tiles")
@CrossOrigin(origins = "*")
public class TileController {

    // Static tiles never change → cache for 7 days in browser + CDN
    private static final String CACHE_CONTROL = "public, max-age=604800, s-maxage=604800, immutable";

    private final MbtilesService mbtilesService;

    public TileController(MbtilesService mbtilesService) {
        this.mbtilesService = mbtilesService;
    }

    /**
     * Health check endpoint — useful for monitoring and verifying the server is alive.
     */
    @GetMapping(value = "/health", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("UP | datasources=" + mbtilesService.getDataSourceCount());
    }

    @GetMapping("/{datasetName}/{z}/{x}/{y}.pbf")
    public ResponseEntity<byte[]> getVectorTile(
            @PathVariable String datasetName,
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        // Quick input validation — reject obviously invalid coordinates
        if (z < 0 || z > 22 || x < 0 || y < 0) {
            return ResponseEntity.badRequest().build();
        }
        int maxCoord = 1 << z;
        if (x >= maxCoord || y >= maxCoord) {
            return ResponseEntity.badRequest().build();
        }

        TileEntry tile = mbtilesService.getTile(datasetName, z, x, y);

        if (tile == null) {
            // Return 204 No Content instead of 404 for missing tiles.
            // This is more appropriate for tile servers: the coordinate is valid
            // but no data exists at this level. Browsers won't log red errors.
            return ResponseEntity.noContent()
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .build();
        }

        // ETag is pre-computed and stored in the cache entry – zero CPU cost here
        String etag = tile.etag();

        // Conditional GET: client already has this tile → send only headers (0 bytes body)
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, etag)
                    .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                    .build();
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "application/x-protobuf");
        headers.set(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL);
        headers.set(HttpHeaders.ETAG, etag);
        headers.set(HttpHeaders.VARY, "Accept-Encoding");

        // gzip flag is pre-computed – no magic-byte inspection on every request
        if (tile.gzipped()) {
            // Tell the browser the payload is already gzip-compressed.
            // This also signals Tomcat NOT to double-compress.
            headers.set(HttpHeaders.CONTENT_ENCODING, "gzip");
        }

        return new ResponseEntity<>(tile.data(), headers, HttpStatus.OK);
    }
}
