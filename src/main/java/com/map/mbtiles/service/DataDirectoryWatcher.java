package com.map.mbtiles.service;

import com.map.mbtiles.config.MbtilesProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 切片存储目录智能文件变动监听器（兼容 Java 8）
 *
 * 基于 Java 8 原生 WatchService 在后台守护线程递归监听数据存储目录（支持配置任意绝对路径或相对路径）及各级子目录。
 * 当检测到 MBTiles / SQLite 文件的拷入、覆盖更新或删除时，自动执行大文件写入防抖（Debounce），
 * 并在写入完成后自动触发元数据探测、按需预热或细粒度单数据集热重载，彻底实现数据维护“零手动干预”。
 */
@Component
public class DataDirectoryWatcher implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DataDirectoryWatcher.class);

    private static final List<String> SUPPORTED_EXTENSIONS = Arrays.asList(
            ".mbtiles",
            ".db",
            ".sqlite",
            ".sqlite3"
    );

    private final MbtilesService mbtilesService;
    private final MbtilesProperties properties;

    private volatile boolean running = false;
    private WatchService watchService;
    private Thread watcherThread;
    private ScheduledExecutorService debounceScheduler;

    /** 规范化物理监听根路径（绝对路径） */
    private Path canonicalRootDir;

    /** 记录 WatchKey 对应的物理目录路径 */
    private final Map<WatchKey, Path> watchKeyPathMap = new ConcurrentHashMap<>();
    /** 防抖任务调度映射表: datasetName -> ScheduledFuture */
    private final Map<String, ScheduledFuture<?>> pendingTasks = new ConcurrentHashMap<>();

    public DataDirectoryWatcher(MbtilesService mbtilesService, MbtilesProperties properties) {
        this.mbtilesService = mbtilesService;
        this.properties = properties;
    }

    /**
     * 获取实际生效的监听目录：
     * 优先使用 watcher.watch-dir（若显式配置）；未配置时自动继承使用 mbtiles.data-dir
     */
    public File getEffectiveWatchDir() {
        if (properties != null && properties.getWatcher() != null) {
            String customDir = properties.getWatcher().getWatchDir();
            if (customDir != null && !customDir.trim().isEmpty()) {
                return new File(customDir.trim());
            }
        }
        if (properties != null && properties.getDataDir() != null) {
            return new File(properties.getDataDir());
        }
        return new File("./data");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }

        if (properties != null && properties.getWatcher() != null && !properties.getWatcher().isEnabled()) {
            log.info("切片存储目录智能监听功能已在配置中禁用。");
            return;
        }

        File watchDir = getEffectiveWatchDir();
        if (!watchDir.exists()) {
            boolean created = watchDir.mkdirs();
            if (created) {
                log.info("配置的切片数据目录不存在，已自动创建: {}", watchDir.getAbsolutePath());
            }
        }

        if (!watchDir.isDirectory()) {
            log.warn("配置的切片存储路径不是有效文件夹，跳过启动文件监听器: {}", watchDir.getAbsolutePath());
            return;
        }

        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            this.debounceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "mbtiles-file-debouncer");
                t.setDaemon(true);
                return t;
            });

            this.canonicalRootDir = watchDir.getCanonicalFile().toPath();
            registerAllDirectories(this.canonicalRootDir);

            this.running = true;
            this.watcherThread = new Thread(this::watchLoop, "mbtiles-file-watcher");
            this.watcherThread.setDaemon(true);
            this.watcherThread.start();

            log.info("切片存储目录智能热感知监听器已就绪，正在实时监听: {}", this.canonicalRootDir);
        } catch (IOException e) {
            log.error("初始化切片存储目录 WatchService 失败 [{}]: {}", watchDir.getAbsolutePath(), e.getMessage(), e);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        log.info("正在停止切片存储目录文件监听器...");

        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                log.debug("关闭 WatchService 异常: {}", e.getMessage());
            }
        }

        if (debounceScheduler != null) {
            debounceScheduler.shutdownNow();
        }

        if (watcherThread != null) {
            watcherThread.interrupt();
        }

        watchKeyPathMap.clear();
        pendingTasks.clear();
        log.info("切片存储目录文件监听器已安全停止。");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // 确保在 Web 容器完全就绪后启动监听
        return Integer.MAX_VALUE;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    /**
     * 递归注册指定目录及其所有下属子目录到 WatchService
     */
    private void registerAllDirectories(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                try {
                    WatchKey key = dir.register(
                            watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE
                    );
                    watchKeyPathMap.put(key, dir);
                } catch (IOException e) {
                    log.warn("无法注册目录监听: {} ({})", dir, e.getMessage());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 后台事件监听主循环
     */
    private void watchLoop() {
        Path rootDir = this.canonicalRootDir != null ? this.canonicalRootDir : getEffectiveWatchDir().toPath();

        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                break;
            }

            Path dir = watchKeyPathMap.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path filename = ev.context();
                Path fullPath = dir.resolve(filename);

                // 如果是新创建了子目录，动态递归注册该子目录
                if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(fullPath, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        registerAllDirectories(fullPath);
                        log.info("检测到新目录创建，已自动追加监听: {}", fullPath);
                    } catch (IOException e) {
                        log.warn("注册新目录失败 {}: {}", fullPath, e.getMessage());
                    }
                    continue;
                }

                // 检查是否属于受支持的切片数据库格式 (.mbtiles, .db, .sqlite, .sqlite3)
                String fileNameStr = filename.toString().toLowerCase();
                boolean isTileFile = false;
                for (String ext : SUPPORTED_EXTENSIONS) {
                    if (fileNameStr.endsWith(ext)) {
                        isTileFile = true;
                        break;
                    }
                }

                if (!isTileFile) {
                    continue;
                }

                // 计算相对路径对应的数据集逻辑名称
                String datasetName = resolveDatasetName(rootDir, fullPath);
                if (datasetName == null || !mbtilesService.isValidDatasetName(datasetName)) {
                    continue;
                }

                // 提交防抖任务调度（等待写入流平稳完毕）
                scheduleDebouncedEvent(kind, fullPath, datasetName);
            }

            boolean valid = key.reset();
            if (!valid) {
                watchKeyPathMap.remove(key);
            }
        }
    }

    /**
     * 防抖任务调度：取消同一数据集未到期的旧任务，重设 debounceMs 后执行
     */
    private void scheduleDebouncedEvent(WatchEvent.Kind<?> kind, Path filePath, String datasetName) {
        long debounceMs = properties != null ? properties.getWatcher().getDebounceMs() : 1500;

        ScheduledFuture<?> existing = pendingTasks.remove(datasetName);
        if (existing != null && !existing.isDone()) {
            existing.cancel(false);
        }

        ScheduledFuture<?> future = debounceScheduler.schedule(() -> {
            try {
                handleFileEvent(kind, filePath, datasetName);
            } catch (Exception e) {
                log.error("处理切片文件变动事件异常 [{}]: {}", datasetName, e.getMessage(), e);
            } finally {
                pendingTasks.remove(datasetName);
            }
        }, debounceMs, TimeUnit.MILLISECONDS);

        pendingTasks.put(datasetName, future);
    }

    /**
     * 文件写入稳定后的实际业务处理逻辑
     */
    private void handleFileEvent(WatchEvent.Kind<?> kind, Path filePath, String datasetName) {
        File file = filePath.toFile();

        if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
            if (file.exists() && file.isFile()) {
                log.info("【文件智能热感知】检测到新切片库接入: {} (大小: {} 字节)", datasetName, file.length());
                // 触发元数据提取与空间范围预计算
                mbtilesService.getDatasetInfo(datasetName);

                // 若开启全局预热，异步触发该新文件的低层级切片批量预热
                if (properties != null && properties.getWarmup().isEnabled()) {
                    int maxZoom = properties.getWarmup().getMaxZoom();
                    log.info("正在为新切片库 '{}' 自动异步执行 0~{} 层级预热...", datasetName, maxZoom);
                    CompletableFuture.runAsync(() -> {
                        int loaded = mbtilesService.warmupDatasetBatch(datasetName, maxZoom);
                        log.info("新切片库 '{}' 预热完成: 共载入 {} 个瓦片", datasetName, loaded);
                    });
                }
            }
        } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
            if (file.exists() && file.isFile()) {
                log.info("【文件智能热感知】检测到切片库被覆盖/修改: {}, 正在自动热重载...", datasetName);
                mbtilesService.reloadDataset(datasetName);
            }
        } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
            log.info("【文件智能热感知】检测到切片库文件被移除: {}, 正在释放连接池与清理缓存...", datasetName);
            mbtilesService.reloadDataset(datasetName);
        }
    }

    /**
     * 将文件物理路径解析为 REST 路由所用的规范化数据集相对名称（去掉已知扩展名，统一使用斜杠）
     * 无论 rootDir 是绝对路径、相对路径还是外部挂载路径均能精准映射
     */
    public String resolveDatasetName(Path rootDir, Path filePath) {
        try {
            Path realRoot = rootDir.toRealPath();
            Path realFile = filePath.toRealPath();
            Path relative = realRoot.relativize(realFile);
            String relStr = relative.toString().replace('\\', '/');
            for (String ext : SUPPORTED_EXTENSIONS) {
                if (relStr.toLowerCase().endsWith(ext)) {
                    return relStr.substring(0, relStr.length() - ext.length());
                }
            }
            return relStr;
        } catch (Exception e) {
            // 文件已被物理删除或移动，使用绝对路径规范化剥离
            String fullStr = filePath.toAbsolutePath().normalize().toString().replace('\\', '/');
            String rootStr = rootDir.toAbsolutePath().normalize().toString().replace('\\', '/');
            if (fullStr.startsWith(rootStr)) {
                String sub = fullStr.substring(rootStr.length());
                if (sub.startsWith("/")) {
                    sub = sub.substring(1);
                }
                for (String ext : SUPPORTED_EXTENSIONS) {
                    if (sub.toLowerCase().endsWith(ext)) {
                        return sub.substring(0, sub.length() - ext.length());
                    }
                }
                return sub;
            }
            return null;
        }
    }
}
