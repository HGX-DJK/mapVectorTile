package com.map.mbtiles.model;

/**
 * 瓦片缓存不可变包装对象（Record）
 * 同时存储瓦片二进制数据及预先计算好的元数据（ETag、是否已 Gzip 压缩），
 * 确保命中缓存时无需重复计算哈希或检查魔数字节，实现零额外 CPU 开销。
 *
 * @param data    瓦片原始二进制字节数组（PBF 格式）
 * @param etag    预先计算好的 ETag 唯一标识符
 * @param gzipped 标记该瓦片在数据库中是否已被 Gzip 压缩
 */
public record TileEntry(
        byte[] data,
        String etag,
        boolean gzipped
) {

    /**
     * 空瓦片单例常量，用于负向缓存防穿透。
     * 当 SQLite 中不存在对应坐标的瓦片时，缓存此单例；
     * 占用极小堆内存（0 字节数组），彻底杜绝空白区域反复穿透到 SQLite。
     */
    public static final TileEntry EMPTY = new TileEntry(new byte[0], "\"empty\"", false);

    /**
     * 判断当前瓦片是否为空瓦片
     */
    public boolean isEmpty() {
        return this.data == null || this.data.length == 0;
    }
}
