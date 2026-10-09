package com.richuang.os.framework.idempotent.config;

import com.richuang.os.framework.idempotent.core.aop.IdempotentAspect;
import com.richuang.os.framework.idempotent.core.keyresolver.impl.DefaultIdempotentKeyResolver;
import com.richuang.os.framework.idempotent.core.keyresolver.impl.ExpressionIdempotentKeyResolver;
import com.richuang.os.framework.idempotent.core.keyresolver.IdempotentKeyResolver;
import com.richuang.os.framework.idempotent.core.keyresolver.impl.UserIdempotentKeyResolver;
import com.richuang.os.framework.idempotent.core.redis.IdempotentRedisDAO;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import com.richuang.os.framework.redis.config.OsRedisAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

@AutoConfiguration(after = OsRedisAutoConfiguration.class)
public class OsIdempotentConfiguration {

    @Bean
    public IdempotentAspect idempotentAspect(List<IdempotentKeyResolver> keyResolvers, IdempotentRedisDAO idempotentRedisDAO) {
        return new IdempotentAspect(keyResolvers, idempotentRedisDAO);
    }

    @Bean
    public IdempotentRedisDAO idempotentRedisDAO(StringRedisTemplate stringRedisTemplate) {
        return new IdempotentRedisDAO(stringRedisTemplate);
    }

    // ========== 各种 IdempotentKeyResolver Bean ==========

    @Bean
    public DefaultIdempotentKeyResolver defaultIdempotentKeyResolver() {
        return new DefaultIdempotentKeyResolver();
    }

    @Bean
    public UserIdempotentKeyResolver userIdempotentKeyResolver() {
        return new UserIdempotentKeyResolver();
    }

    @Bean
    public ExpressionIdempotentKeyResolver expressionIdempotentKeyResolver() {
        return new ExpressionIdempotentKeyResolver();
    }

}
