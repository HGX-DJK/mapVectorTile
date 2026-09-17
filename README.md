# 🗺️ MBTiles Vector Tile Server

高性能矢量瓦片服务器，基于 Spring Boot 构建，从 `.mbtiles`、`.db`、`.sqlite` 等 SQLite 数据库文件读取并提供 PBF / MVT 格式的矢量瓦片数据，原生支持 TileJSON 3.0 规范，具备零数据库负载的负向缓存、多文件自动缩容连接池与空间拓扑剪枝能力。

---

## ✨ 核心特性

- **🚀 毫秒级极速响应** — Caffeine 内存缓存 (50,000 条) + SQLite 2GB mmap 内存映射 I/O，热点瓦片 < 0.1ms 响应。
- **📁 多格式与子目录自适应** — 自动识别并兼容 `.mbtiles`、`.db`、`.sqlite`、`.sqlite3` 等数据库文件，支持多级子文件夹组织（如 `/tiles/admin/beijing/...`）。
- **⚡ 海量文件连接池自动缩容** — 支持数百个数据文件共存；连接池采用 `minIdle = 0` + `idleTimeout = 60s` 机制，空闲连接自动释放归零，并配合 LRU 淘汰机制，杜绝系统文件句柄耗尽。
- **🛡️ 智能预热防缓存踩踏** — 针对多文件场景实施预热限额保护（默认预热前 3 个核心底图，或配置预热白名单），防止上百个文件启动时冲垮 Caffeine 缓存。
- **🎯 空间范围与层级双重短路** — 自动解析数据集 `minzoom`、`maxzoom` 及地理空间边界（BBox），超出物理范围请求零数据库 I/O 直接响应 204。
- **🛡️ 空瓦片负向缓存防穿透** — 使用 `TileEntry.EMPTY` 单例缓存空白网格，彻底阻断大范围无要素区域对 SQLite 的穿透查询。
- **📦 标准 TileJSON 3.0** — 支持 `/tiles/{dataset}/tilejson.json`，MapLibre GL JS / Mapbox GL JS 一行 URL 自动配置。
- **🌐 全面兼容多客户端** — 瓦片接口原生同时支持 `.pbf`、`.mvt` 以及无后缀路由，无缝对接 QGIS、ArcGIS 与 Web 前端。
- **🔄 数据集目录与热重载** — 自动递归发现 `data/` 目录下的所有数据集（`/tiles/datasets`），并支持运行时免停机热重载（`POST /tiles/reload`）。
- **📊 缓存指标实时监控** — 提供 `/tiles/cache-stats` 接口，实时掌握 Caffeine 命中率、缓存量与驱逐指标。
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

### 1. 放置切片数据文件

将你的 `.mbtiles`、`.db` 或 `.sqlite` 文件放入项目根目录的 `data/` 文件夹（支持多层子目录）：

```text
mapVectorTile/
├── data/
│   ├── basemap_line_point.mbtiles    ← 根目录数据集
│   ├── beijing.db                    ← 支持 .db / .sqlite 后缀
│   └── vector/
│       ├── roads.mbtiles             ← 支持子目录分类存放
│       └── buildings.sqlite
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
递归扫描 `data/` 目录，返回所有发现的数据集名称（含子目录相对路径）、文件大小、缩放范围及边界。

---

### 2. 标准 TileJSON 3.0 元数据接口
```http
GET /tiles/{datasetName}/tilejson.json
```
- 支持根目录数据集：`/tiles/basemap_line_point/tilejson.json`
- 支持子目录数据集：`/tiles/vector/roads/tilejson.json`（或别名 `/tiles/vector__roads/tilejson.json`）

**响应示例：**
```json
{
  "tilejson": "3.0.0",
  "name": "basemap_line_point",
  "format": "pbf",
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

### 3. 获取矢量瓦片 (支持 .pbf / .mvt / 无后缀 / 子目录)
```http
GET /tiles/{datasetName}/{z}/{x}/{y}.pbf
GET /tiles/{datasetName}/{z}/{x}/{y}.mvt
GET /tiles/{datasetName}/{z}/{x}/{y}
GET /tiles/{subFolder}/{datasetName}/{z}/{x}/{y}.pbf
```

**示例：**
- 普通获取：`GET /tiles/beijing/10/843/388.pbf`
- 子目录获取：`GET /tiles/vector/roads/10/843/388.mvt`

**响应头：**
- `Content-Type`: `application/x-protobuf`
- `Content-Encoding`: `gzip`（当数据在 MBTiles 中已压缩时）
- `Cache-Control`: `public, max-age=604800, s-maxage=604800, immutable`
- `ETag`: `"<hash>"`（CRC32 硬件加速校验值）
- `Access-Control-Expose-Headers`: `ETag, Content-Length, Content-Encoding`

**状态码说明：**
- `200 OK`: 成功返回瓦片二进制流。
- `204 No Content`: 该坐标不存在数据，或层级/空间超出数据集有效范围（客户端不报红）。
- `304 Not Modified`: 客户端 ETag 匹配（零字节传输）。
- `400 Bad Request`: 非法参数或恶意路径穿越输入。
- `404 Not Found`: 数据集不存在。
- `503 Service Unavailable`: 数据库暂时不可用。

---

### 4. 实时监控与运维热重载接口

#### 实时缓存指标
```http
GET /tiles/cache-stats
```
**响应示例：**
```json
{
  "estimatedSize": 3412,
  "hitCount": 18240,
  "missCount": 1150,
  "hitRate": "94.07%",
  "evictionCount": 0,
  "loadSuccessCount": 1150,
  "activeDataSources": 1
}
```

#### 数据集免停机热重载
```http
POST /tiles/reload
```
响应：`{"status":"success","message":"MBTiles / DB 数据集与缓存已全部热重载"}`

#### 服务健康状态
```http
GET /tiles/health
```
响应：`UP | datasources=1`

---

## ⚙️ 多文件海量数据源核心配置

配置文件位于 `src/main/resources/application.yml`：

```yaml
server:
  port: 8445
  compression:
    enabled: true
    min-response-size: 1024

mbtiles:
  data-dir: ./data              # 数据文件目录（递归检索）
  pool:
    max-size: 15                # 单数据集最大连接数（SQLite 推荐 10~15）
    min-idle: 0                 # 最小空闲连接设为 0：空闲超时后自动释放归零，杜绝文件句柄耗尽
    idle-timeout: 60000         # 60 秒无访问自动释放该连接
    max-active-pools: 50        # 最多常驻 50 个数据源连接池，超出时自动 LRU 淘汰最久未访问池
  warmup:
    enabled: true               # 是否开启启动预热
    max-zoom: 6                 # 预热最大层级
    max-datasets: 3             # 多文件时默认仅预热前 3 个数据集，防止冲垮 Caffeine
    include-datasets: []        # 可选：指定显式预热白名单（如 ["vector/roads", "beijing"]）
  cache-control:
    max-age: 604800             # 浏览器缓存 7 天
    immutable: true

spring:
  cache:
    type: caffeine
    caffeine:
      spec: maximumSize=50000,expireAfterAccess=30m,recordStats
```

---

## 🔌 前端多图层叠加接入示例

### MapLibre GL JS（同时挂载多个数据源组合渲染）

```javascript
const map = new maplibregl.Map({
  container: 'map',
  center: [116.4, 39.9],
  zoom: 10,
  style: {
    version: 8,
    // 1. 同时定义多个独立数据源（支持 .db、.mbtiles、子目录）
    sources: {
      'base-source': {
        type: 'vector',
        url: 'http://localhost:8445/tiles/basemap_line_point/tilejson.json'
      },
      'roads-source': {
        type: 'vector',
        url: 'http://localhost:8445/tiles/vector/roads/tilejson.json'
      }
    },
    // 2. 按图层层叠渲染
    layers: [
      {
        id: 'base-line-layer',
        type: 'line',
        source: 'base-source',
        'source-layer': 'lines',
        paint: { 'line-color': '#94a3b8', 'line-width': 1 }
      },
      {
        id: 'highways-layer',
        type: 'line',
        source: 'roads-source',
        'source-layer': 'highways',
        paint: { 'line-color': '#f59e0b', 'line-width': 2.5 }
      }
    ]
  }
});
```

---

## 📄 License

MIT License
