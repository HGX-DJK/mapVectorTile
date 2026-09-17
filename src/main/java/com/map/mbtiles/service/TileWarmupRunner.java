package com.map.mbtiles.service;

import com.map.mbtiles.config.MbtilesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * 启动预热执行器
 *
 * 在应用启动完成后，自动对各个 MBTiles 数据集的低缩放级别（如 z=0~6）进行单 SQL 批量流式预热，
 * 将热点概览瓦片直接填充至 Caffeine 内存缓存，彻底消除冷启动首次访问延迟。
 * 异步后台执行，完全不阻塞应用本身的就绪启动。
 */
@Component
public class TileWarmupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(TileWarmupRunner.class);

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
            log.info("瓦片预热功能已在配置中禁用。");
            return;
        }

        File dataDir = new File(properties.getDataDir());
        if (!dataDir.exists() || !dataDir.isDirectory()) {
            log.warn("MBTiles 数据存储目录不存在: {}, 跳过预热", dataDir.getAbsolutePath());
            return;
        }

        File[] mbtilesFiles = dataDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".mbtiles"));
        if (mbtilesFiles == null || mbtilesFiles.length == 0) {
            log.info("未发现任何 .mbtiles 数据文件，跳过预热");
            return;
        }

        // 异步后台运行预热，使 HTTP 服务能够瞬间就绪并开始对外响应
        CompletableFuture.runAsync(() -> {
            for (File file : mbtilesFiles) {
                String datasetName = file.getName().substring(0, file.getName().length() - 8);
                warmupDataset(datasetName, warmupProps.getMaxZoom());
            }
        });
    }

    /**
     * 批量预加载指定数据集从 z=0 至 maxZoom 的所有瓦片
     */
    private void warmupDataset(String datasetName, int maxZoom) {
        log.info("正在对数据集 '{}' 执行批量瓦片预热 (层级: 0 ~ {})...", datasetName, maxZoom);
        long start = System.currentTimeMillis();

        int count = mbtilesService.warmupDatasetBatch(datasetName, maxZoom);

        long elapsed = System.currentTimeMillis() - start;
        log.info("数据集 '{}' 批量预热完成: 共将 {} 个瓦片载入内存缓存，耗时 {} 毫秒",
                datasetName, count, elapsed);
    }
}
