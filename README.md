# 🗺️ MBTiles Vector Tile Server

高性能矢量瓦片服务器，基于 Spring Boot 构建，从 `.mbtiles` 文件读取并提供 PBF 格式的矢量瓦片数据。

---

## ✨ 特性

- **HTTP/2 + HTTPS** — 多路复用消除浏览器 6 连接并发限制
- **Caffeine 内存缓存** — 50,000 条瓦片热缓存，重复请求毫秒级响应
- **ETag + 304 Not Modified** — 浏览器条件请求，零传输验证
- **Cache-Control 7 天** — 静态瓦片长期浏览器本地缓存
- **SQLite 深度调优** — 自动索引、WAL 模式、mmap 内存映射 I/O
- **HikariCP 连接池** — 20 连接、5 秒快速超时、自动回收
- **启动预加载** — 自动预热 z=0~6 低缩放级别瓦片
- **全局异常处理** — 所有错误返回合理 HTTP 状态码，杜绝 `Failed to fetch`
- **Gzip 智能处理** — 自动检测已压缩瓦片，避免二次压缩

---

## 📋 环境要求

| 工具 | 版本 |
|------|------|
| **JDK** | 17+ |
| **Maven** | 3.8+ |
| **操作系统** | Windows / Linux / macOS |

---

## 🚀 快速开始

### 1. 克隆项目

```bash
git clone <repository-url>
cd mapVectorTile
```

### 2. 放置 MBTiles 数据文件

将你的 `.mbtiles` 文件放入项目根目录的 `data/` 文件夹：

```
mapVectorTile/
├── data/
│   ├── basemap_line_point.mbtiles    ← 你的瓦片数据
│   └── another_dataset.mbtiles       ← 支持多个数据集
├── src/
├── pom.xml
└── README.md
```

### 3. 编译 & 运行

```bash
# 编译
mvn clean package -DskipTests

# 运行（需在项目根目录执行，因为 data-dir 是相对路径）
java -jar target/mbtiles-server-0.0.1-SNAPSHOT.jar
```

或使用 Maven 直接启动：

```bash
mvn spring-boot:run
```

### 4. 访问瓦片

服务启动后默认监听 `https://127.0.0.1:8443`。

> ⚠️ **首次访问 HTTPS**：由于使用自签名证书，需先在浏览器中打开 `https://127.0.0.1:8443/tiles/health`，手动信任证书（点击"高级" → "继续访问"）。Chrome 用户如未看到继续按钮，可直接在页面上键入 `thisisunsafe`。

---

## 📡 API 接口

### 获取矢量瓦片

```
GET /tiles/{datasetName}/{z}/{x}/{y}.pbf
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `datasetName` | String | `.mbtiles` 文件名（**不含扩展名**） |
| `z` | int | 缩放级别（0–22） |
| `x` | int | 瓦片列号（0 ~ 2^z - 1） |
| `y` | int | 瓦片行号（XYZ 坐标系，自动转 TMS） |

**响应头：**

| Header | 值 | 说明 |
|--------|-----|------|
| `Content-Type` | `application/x-protobuf` | PBF 矢量瓦片 |
| `Content-Encoding` | `gzip`（如适用） | 仅当瓦片本身已压缩时设置 |
| `Cache-Control` | `public, max-age=604800, immutable` | 浏览器缓存 7 天 |
| `ETag` | `"<hash>"` | 基于内容的唯一标识 |
| `Vary` | `Accept-Encoding` | CDN 分别缓存 |

**示例：**

```bash
# 获取 basemap_line_point 数据集的 z=10, x=857, y=418 瓦片
curl -k https://127.0.0.1:8443/tiles/basemap_line_point/10/857/418.pbf

# 条件请求（如果 ETag 匹配则返回 304）
curl -k -H 'If-None-Match: "1a2b3c4d"' \
  https://127.0.0.1:8443/tiles/basemap_line_point/10/857/418.pbf
```

**状态码：**

| 状态码 | 含义 |
|--------|------|
| `200` | 成功返回瓦片数据 |
| `204` | 该坐标无瓦片数据（正常情况，非错误） |
| `304` | 瓦片未修改（ETag 匹配） |
| `400` | 参数无效（z/x/y 超出范围） |
| `503` | 数据库暂时不可用 |

---

### 健康检查

```
GET /tiles/health
```

```bash
curl -k https://127.0.0.1:8443/tiles/health
# 响应: UP | datasources=1
```

---

## ⚙️ 配置说明

配置文件位于 `src/main/resources/application.yml`：

```yaml
server:
  port: 8443                    # 服务端口
  http2:
    enabled: true               # HTTP/2 多路复用
  ssl:
    enabled: true               # HTTPS
    key-store: classpath:keystore.p12
    key-store-password: 123456
    key-store-type: PKCS12
    key-alias: maptile
  compression:
    enabled: true               # HTTP 响应压缩
    mime-types: application/x-protobuf,application/vnd.mapbox-vector-tile,...
    min-response-size: 1024     # ≥1KB 的响应才压缩
  tomcat:
    threads:
      max: 200                  # 最大工作线程
      min-spare: 20             # 最小空闲线程
    max-connections: 10000      # 最大连接数
    accept-count: 300           # 等待队列长度

mbtiles:
  data-dir: ./data              # MBTiles 数据目录（相对于启动目录）

spring:
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=50000,expireAfterAccess=30m,recordStats
```

### 关键配置项

| 配置 | 默认值 | 说明 |
|------|--------|------|
| `mbtiles.data-dir` | `./data` | MBTiles 文件目录，支持相对/绝对路径 |
| `spring.cache.caffeine.spec` | 见上 | `maximumSize` 最大缓存条数，`expireAfterAccess` 空闲过期时间 |
| `server.port` | `8443` | HTTPS 端口 |

### 使用绝对路径（推荐用于生产环境）

如果通过 `java -jar` 在非项目目录启动，建议使用绝对路径：

```yaml
mbtiles:
  data-dir: D:/地图引擎/mapVectorTile/data
```

### 切换到 HTTP（无 HTTPS）

如果不需要 HTTPS，删除 ssl 配置块并修改端口：

```yaml
server:
  port: 8080
  http2:
    enabled: false    # HTTP/2 需要 HTTPS，关闭 SSL 时需一起关闭
  # ssl:              ← 注释或删除整个 ssl 块
```

---

## 🏗️ 项目结构

```
mapVectorTile/
├── data/                                    # MBTiles 数据文件目录
│   └── basemap_line_point.mbtiles
├── src/main/java/com/map/mbtiles/
│   ├── MbtilesApplication.java              # Spring Boot 启动类
│   ├── config/
│   │   └── MbtilesProperties.java           # 配置属性映射
│   ├── controller/
│   │   ├── TileController.java              # 瓦片 REST API
│   │   └── GlobalExceptionHandler.java      # 全局异常处理
│   └── service/
│       ├── MbtilesService.java              # 核心业务：SQLite 查询 + 缓存
│       ├── TileEntry.java                   # 瓦片缓存包装对象
│       └── TileWarmupRunner.java            # 启动预加载
├── src/main/resources/
│   ├── application.yml                      # 应用配置
│   └── keystore.p12                         # HTTPS 自签名证书
└── pom.xml                                  # Maven 依赖管理
```

---

## 🔧 性能架构

### 请求链路

```
浏览器请求 ──→ 浏览器本地缓存 (7天)
                 │ 未命中
                 ▼
             HTTP ETag 校验 ──→ 304 Not Modified (0字节传输)
                 │ 未命中
                 ▼
             Caffeine 内存缓存 (50000条) ──→ 200 OK (命中，~0.1ms)
                 │ 未命中
                 ▼
             HikariCP 连接池 (20连接)
                 │
                 ▼
             SQLite 查询 (索引 + mmap I/O) ──→ 200 OK (~5-15ms)
```

### 四级缓存体系

| 层级 | 技术 | 命中时延 | 容量 |
|------|------|----------|------|
| L1 | 浏览器 `Cache-Control` | **0ms** | 无限（磁盘） |
| L2 | HTTP `ETag` / `304` | **~1ms** | 无限 |
| L3 | Caffeine JVM 堆内存 | **~0.1ms** | 50,000 条 |
| L4 | SQLite + mmap I/O | **~5-15ms** | 完整数据库 |

### SQLite 优化项

| 优化 | 说明 |
|------|------|
| 自动创建索引 | `(zoom_level, tile_column, tile_row)` 唯一索引 |
| WAL 模式 | 读写分离，提升并发读取 |
| 10000 页缓存 | ~40MB SQLite 内部页缓存 |
| 64KB 页大小 | 大 BLOB 读取减少 I/O 次数 |
| 2GB mmap | 内存映射文件，利用 OS 页缓存 |
| 同步关闭 | `synchronous=OFF`，只读安全 |

---

## 🔌 前端接入

### MapLibre GL JS

```javascript
const map = new maplibregl.Map({
  container: 'map',
  style: {
    version: 8,
    sources: {
      'mbtiles-source': {
        type: 'vector',
        tiles: ['https://127.0.0.1:8443/tiles/basemap_line_point/{z}/{x}/{y}.pbf'],
        maxzoom: 14
      }
    },
    layers: [{
      id: 'lines',
      type: 'line',
      source: 'mbtiles-source',
      'source-layer': 'your_layer_name',  // 替换为你的图层名
      paint: { 'line-color': '#ff0000' }
    }]
  }
});
```

### Mapbox GL JS

```javascript
map.addSource('mbtiles-source', {
  type: 'vector',
  tiles: ['https://127.0.0.1:8443/tiles/basemap_line_point/{z}/{x}/{y}.pbf'],
  maxzoom: 14
});
```

### OpenLayers

```javascript
import VectorTileLayer from 'ol/layer/VectorTile';
import VectorTileSource from 'ol/source/VectorTile';
import MVT from 'ol/format/MVT';

const layer = new VectorTileLayer({
  source: new VectorTileSource({
    format: new MVT(),
    url: 'https://127.0.0.1:8443/tiles/basemap_line_point/{z}/{x}/{y}.pbf'
  })
});
```

---

## 🛠️ 常见问题

### Q: 浏览器报 `TypeError: Failed to fetch`

**可能原因及解决方案：**

1. **HTTPS 证书不受信任**（最常见）
   - 先在浏览器打开 `https://127.0.0.1:8443/tiles/health`，手动接受证书
   - 或改用 HTTP 模式（见配置说明）

2. **连接池耗尽**
   - 检查日志是否有 `Connection is not available` 错误
   - 增大 `maximumPoolSize`（默认已设为 20）

3. **瓦片坐标超出范围**
   - 服务器会返回 `204 No Content`，不会引发 fetch 错误

### Q: 首次请求很慢

- 首次启动时 `TileWarmupRunner` 会自动预加载 z=0~6 的瓦片
- 后续请求命中 Caffeine 缓存，响应时间 < 1ms
- 检查是否创建了 SQLite 索引（启动日志中查看 `Verified/Created tile_index`）

### Q: `不支持发行版本5` 编译错误

`pom.xml` 中已配置 `<java.version>17</java.version>`，确保：
- 系统 `JAVA_HOME` 指向 JDK 17+
- Maven 使用的 JDK 版本正确：`mvn -version`

### Q: `data-dir` 找不到数据

`./data` 是相对路径，相对于**启动命令的工作目录**。如果用 `java -jar` 启动，请确保先 `cd` 到项目根目录，或在 `application.yml` 中使用绝对路径。

---

## 📦 依赖清单

| 依赖 | 用途 |
|------|------|
| `spring-boot-starter-web` | Web 框架 + 内嵌 Tomcat |
| `spring-boot-starter-cache` | 缓存抽象层 |
| `caffeine` | 高性能 JVM 内存缓存 |
| `sqlite-jdbc` | SQLite JDBC 驱动 |
| `HikariCP` | JDBC 连接池 |
| `lombok` | 减少样板代码 |

---

## 📄 License

MIT License
