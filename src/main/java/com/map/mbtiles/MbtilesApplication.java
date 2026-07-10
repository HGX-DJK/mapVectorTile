package com.map.mbtiles;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class MbtilesApplication {

    public static void main(String[] args) {
        SpringApplication.run(MbtilesApplication.class, args);
    }
}
