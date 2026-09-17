package com.map.mbtiles.service;

import com.map.mbtiles.config.MbtilesProperties;
import com.map.mbtiles.model.DatasetInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 启动预热执行器
 *
 * 在应用启动完成后，自动对核心数据集的低缩放级别（如 z=0~6）进行单 SQL 批量流式预热，
 * 将热点概览瓦片直接填充至 Caffeine 内存缓存，彻底消除冷启动首次访问延迟。
 *
 * 针对多文件海量数据源场景实施智能限额（默认仅预热核心/前 N 个数据集），
 * 避免成百上千个数据集同时启动引发的磁盘 I/O 拥塞及 Caffeine 缓存踩踏。
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

        List<DatasetInfo> datasets = mbtilesService.listDatasets();
        if (datasets.isEmpty()) {
            log.info("未发现任何有效矢量切片数据文件（.mbtiles / .db / .sqlite），跳过启动预热");
            return;
        }

        // 筛选待预热的数据集列表（防多文件海量场景下的缓存挤兑）
        List<String> targetNames;
        List<String> allowlist = warmupProps.getIncludeDatasets();

        if (allowlist != null && !allowlist.isEmpty()) {
            // 优先采用配置的显式白名单
            targetNames = allowlist;
            log.info("已配置预热白名单，将预热指定的数据集: {}", targetNames);
        } else {
            // 未配置白名单时，至多预热前 maxDatasets 个数据集（默认 3 个）
            int limit = Math.min(datasets.size(), warmupProps.getMaxDatasets());
            targetNames = datasets.stream()
                    .map(DatasetInfo::getName)
                    .limit(limit)
                    .toList();
            log.info("检测到 {} 个数据集，按策略预热前 {} 个核心数据集: {}", datasets.size(), limit, targetNames);
        }

        // 异步后台运行预热，使 HTTP 服务能够瞬间就绪并开始对外响应
        CompletableFuture.runAsync(() -> {
            for (String datasetName : targetNames) {
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
