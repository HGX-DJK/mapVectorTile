package com.map.mbtiles.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Warms up the Caffeine tile cache on application startup.
 *
 * Pre-loads tiles for zoom levels 0–6 (low-zoom overview tiles that are
 * almost always requested first when a map loads). This eliminates the
 * cold-start penalty where the first few seconds of map browsing hit
 * SQLite for every single tile.
 *
 * Runs asynchronously so it does NOT delay application startup.
 */
@Slf4j
@Component
public class TileWarmupRunner implements ApplicationRunner {

    private final MbtilesService mbtilesService;
    private final com.map.mbtiles.config.MbtilesProperties properties;

    public TileWarmupRunner(MbtilesService mbtilesService,
                            com.map.mbtiles.config.MbtilesProperties properties) {
        this.mbtilesService = mbtilesService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        File dataDir = new File(properties.getDataDir());
        if (!dataDir.exists() || !dataDir.isDirectory()) {
            log.warn("Data directory not found: {}, skipping warmup", dataDir.getAbsolutePath());
            return;
        }

        File[] mbtilesFiles = dataDir.listFiles((dir, name) -> name.endsWith(".mbtiles"));
        if (mbtilesFiles == null || mbtilesFiles.length == 0) {
            log.info("No .mbtiles files found, skipping warmup");
            return;
        }

        // Run warmup asynchronously so the server starts accepting requests immediately
        CompletableFuture.runAsync(() -> {
            for (File file : mbtilesFiles) {
                String datasetName = file.getName().replace(".mbtiles", "");
                warmupDataset(datasetName);
            }
        });
    }

    /**
     * Pre-load tiles for zoom levels 0–6.
     * z=0 has 1 tile, z=6 has 4096 tiles → total ≤ 5461 tiles per dataset.
     */
    private void warmupDataset(String datasetName) {
        log.info("Starting tile warmup for dataset: {}", datasetName);
        AtomicInteger loaded = new AtomicInteger(0);
        AtomicInteger empty = new AtomicInteger(0);

        long start = System.currentTimeMillis();

        for (int z = 0; z <= 6; z++) {
            int size = 1 << z;  // 2^z
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    TileEntry tile = mbtilesService.getTile(datasetName, z, x, y);
                    if (tile != null) {
                        loaded.incrementAndGet();
                    } else {
                        empty.incrementAndGet();
                    }
                }
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Warmup complete for '{}': {} tiles loaded, {} empty, took {}ms",
                datasetName, loaded.get(), empty.get(), elapsed);
    }
}
