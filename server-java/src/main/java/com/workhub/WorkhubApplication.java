package com.workhub;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 工作管理台 · SpringBoot 版后端。
 *
 * 与 server/workhub.py（Python 标准库版）接口、数据库结构、密码算法完全兼容：
 * 前端一行不用改，库文件可以直接互换。
 */
@SpringBootApplication
public class WorkhubApplication {

    public static void main(String[] args) {
        bindServerEndpoint();
        SpringApplication.run(WorkhubApplication.class, args);
    }

    /**
     * 把监听地址/端口钉死，避免被宿主环境变量意外改写。
     *
     * <p>背景：某些宿主环境（CI、IDE 插件、容器编排）会注入 {@code SERVER__PORT} /
     * {@code SERVER_PORT} 之类变量。Spring Boot 的宽松绑定会把它们当成
     * {@code server.port}，且<b>环境变量优先级高于 application.yml</b>，
     * 导致应用被顶到别的端口（曾出现被顶到 53334、直接启动失败）。
     *
     * <p>这里用 Java System Property 显式赋值。在 Spring Boot 的属性来源顺序中，
     * System Property 高于环境变量，因此可以稳定压住宿主注入值；
     * 而命令行 {@code --server.port=xxxx} 优先级仍高于 System Property，
     * 需要临时改端口时依旧可以直接传参。
     *
     * <p>最终优先级：命令行参数 &gt; WORKHUB_PORT/WORKHUB_HOST 环境变量 &gt; 默认值。
     */
    private static void bindServerEndpoint() {
        applyIfAbsent("server.port", env("WORKHUB_PORT", "8098"));
        applyIfAbsent("server.address", env("WORKHUB_HOST", "127.0.0.1"));
    }

    /** 仅在没有显式 -D 定义时写入，避免覆盖用户的 JVM 参数。 */
    private static void applyIfAbsent(String key, String value) {
        if (System.getProperty(key) == null && value != null && !value.isBlank()) {
            System.setProperty(key, value.trim());
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
