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

    /**
     * 根据二进制前导魔数（Magic Number）智能推断真实的 MIME Content-Type
     * 纳秒级自动区分 PNG、JPEG、WebP 栅格图片与 MVT/PBF 矢量切片
     */
    public String detectContentType() {
        if (data == null || data.length < 4) {
            return "application/x-protobuf";
        }
        // PNG 图片魔数: 89 50 4E 47 (\x89PNG)
        if (data[0] == (byte) 0x89 && data[1] == (byte) 0x50 && data[2] == (byte) 0x4E && data[3] == (byte) 0x47) {
            return "image/png";
        }
        // JPEG 图片魔数: FF D8 FF
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // WebP 图片魔数: RIFF....WEBP
        if (data.length >= 12
                && data[0] == (byte) 'R' && data[1] == (byte) 'I' && data[2] == (byte) 'F' && data[3] == (byte) 'F'
                && data[8] == (byte) 'W' && data[9] == (byte) 'E' && data[10] == (byte) 'B' && data[11] == (byte) 'P') {
            return "image/webp";
        }
        // 默认矢量切片
        return "application/x-protobuf";
    }
}
