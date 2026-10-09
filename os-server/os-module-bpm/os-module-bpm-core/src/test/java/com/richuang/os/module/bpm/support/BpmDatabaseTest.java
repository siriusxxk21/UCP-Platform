package com.richuang.os.module.bpm.support;

import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.richuang.os.framework.mybatis.config.OsMybatisAutoConfiguration;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 流程定义持久化测试：复用启动模块配置、当前开发库及底座 MyBatis。 每项测试使用独立前缀和负数夹具 ID，整个事务由 Spring Test 回滚；不建库、不清表。 */
@SpringBootTest(
        classes = BpmDatabaseTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "os.info.base-package=com.richuang.os.module.bpm.dal.mysql.category,com.richuang.os.module.bpm.dal.mysql.definition",
            "mybatis-plus.mapper-locations=classpath*:mapper/bpm-test-only/*.xml",
            "spring.sql.init.mode=never",
            "spring.main.banner-mode=off"
        })
@Transactional
public abstract class BpmDatabaseTest {

    protected final String prefix = "bpm_test_" + UUID.randomUUID().toString().replace("-", "");
    private final AtomicLong ids =
            new AtomicLong(-Math.abs(UUID.randomUUID().getMostSignificantBits() / 2));

    protected long nextId() {
        return ids.decrementAndGet();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @ImportAutoConfiguration({
        DynamicDataSourceAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        OsMybatisAutoConfiguration.class,
        MybatisPlusAutoConfiguration.class
    })
    static class Config {}
}
