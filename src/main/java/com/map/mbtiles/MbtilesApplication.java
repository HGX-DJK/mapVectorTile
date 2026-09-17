package com.map.mbtiles;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * 矢量瓦片服务端启动入口类
 * 开启 Spring Cache 缓存注解支持
 */
@SpringBootApplication
@EnableCaching
public class MbtilesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MbtilesApplication.class, args);
    }
}
