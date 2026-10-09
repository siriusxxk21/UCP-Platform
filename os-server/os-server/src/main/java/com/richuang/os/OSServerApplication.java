package com.richuang.os;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 服务端启动入口，装配当前业务模块并排除旧版系统模块的组件扫描。 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@ComponentScan(
        basePackages = "com.richuang.os",
        excludeFilters = {
            @ComponentScan.Filter(
                    type = FilterType.REGEX,
                    pattern = "com.richuang.os.module.system.legacy\\..*")
        })
public class OSServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(OSServerApplication.class, args);
    }
}
