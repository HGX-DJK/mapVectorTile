package com.map.mbtiles.service;

import com.map.mbtiles.config.MbtilesProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Warms up the Caffeine tile cache on application startup.
 *
 * Uses a single range query per dataset to batch-preload tiles up to maxZoom,
 * eliminating thousands of sequential SQLite roundtrips.
 * Runs asynchronously so it does NOT delay application startup.
 */
@Slf4j
@Component
public class TileWarmupRunner implements ApplicationRunner {

    private final MbtilesService mbtilesService;
    private final MbtilesProperties properties;

    public TileWarmupRunner(MbtilesService mbtilesService, MbtilesProperties properties) {
        this.mbtilesService = mbtilesService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        MbtilesProperties.WarmupProperties warmupProps = properties.getWarmup();
        if (!warmupProps.isEnabled()) {
            log.info("Tile warmup is disabled in configuration.");
            return;
        }

        File dataDir = new File(properties.getDataDir());
        if (!dataDir.exists() || !dataDir.isDirectory()) {
            log.warn("Data directory not found: {}, skipping warmup", dataDir.getAbsolutePath());
            return;
        }

        File[] mbtilesFiles = dataDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".mbtiles"));
        if (mbtilesFiles == null || mbtilesFiles.length == 0) {
            log.info("No .mbtiles files found, skipping warmup");
            return;
        }

        // Run warmup asynchronously so the server starts accepting requests immediately
        CompletableFuture.runAsync(() -> {
            for (File file : mbtilesFiles) {
                String datasetName = file.getName().substring(0, file.getName().length() - 8);
                warmupDataset(datasetName, warmupProps.getMaxZoom());
            }
        });
    }

    /**
     * Batch pre-loads tiles for zoom levels 0 up to maxZoom.
     */
    private void warmupDataset(String datasetName, int maxZoom) {
        log.info("Starting fast batch tile warmup for dataset '{}' (zoom 0~{})...", datasetName, maxZoom);
        long start = System.currentTimeMillis();

        int count = mbtilesService.warmupDatasetBatch(datasetName, maxZoom);

        long elapsed = System.currentTimeMillis() - start;
        log.info("Batch warmup complete for '{}': {} tiles preloaded into cache in {} ms",
                datasetName, count, elapsed);
    }
}
