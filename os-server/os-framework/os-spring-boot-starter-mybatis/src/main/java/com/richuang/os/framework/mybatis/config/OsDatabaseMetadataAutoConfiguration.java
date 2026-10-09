package com.richuang.os.framework.mybatis.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.richuang.os.framework.mybatis.core.metadata.DatabaseMetadataReader;
import com.richuang.os.framework.mybatis.core.metadata.PostgreSqlDatabaseMetadataMapper;
import com.richuang.os.framework.mybatis.core.metadata.PostgreSqlDatabaseMetadataReader;
import com.richuang.os.framework.mybatis.core.util.JdbcUtils;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** 为当前 PostgreSQL 主数据源装配只读目录服务；沿用底座数据库类型识别，不新增连接配置。 */
@AutoConfiguration(after = OsMybatisAutoConfiguration.class)
@Conditional(OsDatabaseMetadataAutoConfiguration.PostgreSqlCondition.class)
public class OsDatabaseMetadataAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(DatabaseMetadataReader.class)
    public DatabaseMetadataReader databaseMetadataReader(PostgreSqlDatabaseMetadataMapper mapper) {
        return new PostgreSqlDatabaseMetadataReader(mapper);
    }

    static class PostgreSqlCondition extends SpringBootCondition {
        @Override
        public ConditionOutcome getMatchOutcome(
                ConditionContext context, AnnotatedTypeMetadata metadata) {
            DbType dbType = null;
            if (context.getEnvironment() instanceof ConfigurableEnvironment environment) {
                dbType = IdTypeEnvironmentPostProcessor.getDbType(environment);
                if (dbType == null) {
                    // dynamic-datasource 未显式配置 primary 时使用 master，与现有底座实际配置一致。
                    String primary =
                            environment.getProperty("spring.datasource.dynamic.primary", "master");
                    String url =
                            environment.getProperty(
                                    "spring.datasource.dynamic.datasource." + primary + ".url");
                    if (url != null && !url.isBlank()) dbType = JdbcUtils.getDbType(url);
                }
            }
            boolean matches = dbType == DbType.POSTGRE_SQL;
            return new ConditionOutcome(matches, "当前主数据源为 PostgreSQL 才启用目录适配");
        }
    }
}
