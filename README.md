# 🗺️ MBTiles Vector Tile Server

高性能矢量瓦片服务器，基于 Spring Boot 构建，从 `.mbtiles` 文件读取并提供 PBF 格式的矢量瓦片数据，原生支持 TileJSON 3.0 规范。

---

## ✨ 核心特性

- **🚀 毫秒级极速响应** — Caffeine 内存缓存 (50,000 条) + SQLite 2GB mmap 内存映射 I/O，热点瓦片 < 0.1ms 响应。
- **⚡ 单 SQL 批量预热** — 启动时使用单条范围查询秒级预载低缩放级别瓦片，彻底告别冷启动抖动。
- **🎯 Zoom 层级前置短路** — 自动解析数据集 `minzoom` 与 `maxzoom`，超出层级请求零数据库 I/O 直接响应 204。
- **📦 标准 TileJSON 3.0** — 支持 `/tiles/{dataset}/tilejson.json`，MapLibre GL JS / Mapbox GL JS 一行 URL 自动配置。
- **📁 数据集目录发现** — 自动扫描 `data/` 目录，通过 `/tiles/datasets` 接口提供动态数据集元数据目录。
- **🛡️ 生产级安全防护** — 严密防范路径穿越（Path Traversal）漏洞，白名单字符与标准路径双重校验。
- **🔄 RFC 7232 条件请求** — 硬件加速 CRC32 ETag 生成，支持弱 ETag（`W/`）与多 ETag 识别，精准返回 304 零传输。
- **🔇 异常优雅降级** — 自动静默捕获地图拖拽缩放产生的 `ClientAbortException` / `CloseNowException`，杜绝日志刷屏。
- **🧩 Schema 全兼容** — 自动兼容扁平 Table 模式与 Tippecanoe View（视图）模式，针对性构建索引并激活 WAL。

---

## 📋 环境要求

| 工具 | 版本 |
|------|------|
| **JDK** | 17+ (推荐 21+ 启用虚拟线程) |
| **Maven** | 3.8+ |
| **操作系统** | Windows / Linux / macOS |

---

## 🚀 快速开始

### 1. 放置 MBTiles 数据文件

将你的 `.mbtiles` 文件放入项目根目录的 `data/` 文件夹：

```text
mapVectorTile/
├── data/
│   ├── basemap_line_point.mbtiles    ← 你的瓦片数据
│   └── another_dataset.mbtiles       ← 支持多个数据集
```

### 2. 编译 & 运行

```bash
# 编译打包
mvn clean package -DskipTests

# 运行（可直接在根目录执行）
java -jar target/mbtiles-server-0.0.1-SNAPSHOT.jar
```

或使用 Maven 直接启动：

```bash
mvn spring-boot:run
```

---

## 📡 API 接口说明

### 1. 数据集目录接口
```http
GET /tiles/datasets
GET /tiles
```
返回所有已发现数据集的名称、文件大小、缩放范围、边界范围及图层信息。

---

### 2. 标准 TileJSON 3.0 元数据接口
```http
GET /tiles/{datasetName}/tilejson.json
```
**示例：**
```bash
curl http://127.0.0.1:8445/tiles/basemap_line_point/tilejson.json
```
**响应示例：**
```json
{
  "tilejson": "3.0.0",
  "name": "basemap_line_point",
  "scheme": "xyz",
  "tiles": [
    "http://127.0.0.1:8445/tiles/basemap_line_point/{z}/{x}/{y}.pbf"
  ],
  "minzoom": 0,
  "maxzoom": 14,
  "bounds": [-180, -85.05112878, 180, 85.05112878],
  "center": [116.4, 39.9, 10],
  "vector_layers": [
    { "id": "line", "fields": {} },
    { "id": "point", "fields": {} }
  ]
}
```

---

### 3. 获取矢量瓦片 (PBF)
```http
GET /tiles/{datasetName}/{z}/{x}/{y}.pbf
```

| 参数 | 类型 | 说明 |
|------|------|------|
| `datasetName` | String | `.mbtiles` 文件名（不含扩展名，需满足 `[a-zA-Z0-9_-]`） |
| `z` | int | 缩放级别（0–22） |
| `x` | int | 瓦片列号（0 ~ 2^z - 1） |
| `y` | int | 瓦片行号（XYZ 坐标系，服务端自动转为 MBTiles TMS 坐标） |

**响应头：**
- `Content-Type`: `application/x-protobuf`
- `Content-Encoding`: `gzip`（当数据在 MBTiles 中已压缩时）
- `Cache-Control`: `public, max-age=604800, s-maxage=604800, immutable`
- `ETag`: `"<hash>"`（CRC32 硬件加速校验值）
- `Access-Control-Expose-Headers`: `ETag, Content-Length, Content-Encoding`

**状态码说明：**
- `200 OK`: 成功返回瓦片二进制流。
- `204 No Content`: 该坐标不存在数据，或层级超出数据集有效范围（客户端不报红）。
- `304 Not Modified`: 客户端 ETag 匹配（零字节传输）。
- `400 Bad Request`: 非法参数或恶意路径穿越输入。
- `404 Not Found`: 数据集不存在。
- `503 Service Unavailable`: 数据库暂时不可用。

---

### 4. 服务健康状态
```http
GET /tiles/health
```
响应：`UP | datasources=1`

---

## ⚙️ 配置说明

配置文件位于 `src/main/resources/application.yml`：

```yaml
server:
  port: 8445                    # 监听端口
  http2:
    enabled: false               # HTTP/2 多路复用（开启需配合 HTTPS）
  ssl:
    enabled: false               # 是否启用 HTTPS
  compression:
    enabled: true               # HTTP 响应压缩
    min-response-size: 1024

mbtiles:
  data-dir: ./data              # MBTiles 数据存放路径（相对或绝对路径）
  pool:
    max-size: 20                # 单数据集最大连接数（SQLite 推荐 10~20）
    min-idle: 5                 # 最小空闲连接
    connection-timeout: 30000   # 获取连接超时（毫秒）
  warmup:
    enabled: true               # 启动时是否自动预热
    max-zoom: 6                 # 预热最大缩放层级（单 SQL 批量预加载）
  cache-control:
    max-age: 604800             # 浏览器缓存时长（默认 7 天）
    immutable: true

spring:
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=50000,expireAfterAccess=30m,recordStats
```

---

## 🔌 前端接入示例

### 1. MapLibre GL JS（推荐：通过 TileJSON 一行接入）

```javascript
const map = new maplibregl.Map({
  container: 'map',
  style: {
    version: 8,
    sources: {
      'mbtiles-source': {
        type: 'vector',
        url: 'http://localhost:8445/tiles/basemap_line_point/tilejson.json'
      }
    },
    layers: [
      {
        id: 'lines-layer',
        type: 'line',
        source: 'mbtiles-source',
        'source-layer': 'lines', // MBTiles 中的 vector_layer 名称
        paint: { 'line-color': '#3b82f6', 'line-width': 1.5 }
      }
    ]
  }
});
```

### 2. Mapbox GL JS（模板 URL 方式）

```javascript
map.addSource('mbtiles-source', {
  type: 'vector',
  tiles: ['http://localhost:8445/tiles/basemap_line_point/{z}/{x}/{y}.pbf'],
  maxzoom: 14
});
```

### 3. OpenLayers

```javascript
import VectorTileLayer from 'ol/layer/VectorTile';
import VectorTileSource from 'ol/source/VectorTile';
import MVT from 'ol/format/MVT';

const layer = new VectorTileLayer({
  source: new VectorTileSource({
    format: new MVT(),
    url: 'http://localhost:8445/tiles/basemap_line_point/{z}/{x}/{y}.pbf'
  })
});
```

---

## 🔧 性能架构

```text
浏览器请求 ──→ 浏览器本地缓存 (7天)
                 │ 未命中
                 ▼
             HTTP ETag 校验 ──→ 304 Not Modified (RFC 7232 规范匹配，0 字节传输)
                 │ 未命中
                 ▼
             Zoom 层级短路校验 ──→ 204 No Content (超出 minzoom~maxzoom 零 I/O 返回)
                 │ 合法层级
                 ▼
             Caffeine 内存缓存 (50,000 条) ──→ 200 OK (命中，~0.1ms)
                 │ 未命中
                 ▼
             HikariCP 连接池 (10~20 连接)
                 │
                 ▼
             SQLite 查询 (WAL + 联合索引 + 2GB mmap I/O) ──→ 200 OK (~5-15ms)
```

---

## 📄 License

MIT License
