package com.map.mbtiles.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局 Web MVC 基础配置类
 * 统一配置跨域资源共享（CORS），确保所有瓦片请求（包含 404/500 等错误响应以及预检 OPTIONS 请求）
 * 均能可靠携带标准跨域头，彻底杜绝前端浏览器的跨域拦截异常。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "HEAD", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(
                        HttpHeaders.ETAG,
                        HttpHeaders.CONTENT_LENGTH,
                        HttpHeaders.CONTENT_ENCODING,
                        HttpHeaders.CONTENT_TYPE
                )
                .maxAge(86400); // 浏览器 OPTIONS 预检请求缓存 24 小时，避免冗余往返
    }
}
