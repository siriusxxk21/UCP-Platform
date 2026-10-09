package com.lingan.ucp.framework.redis.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 无需连接 Redis，验证当前类型与日期字段的 JSON 缓存序列化。 */
class OsRedisAutoConfigurationTest {

    public static class CachedValue {
        public String text;
        public LocalDateTime updatedAt;
    }

    @Test
    void preservesTypesNestedInCollectionsAndArrays() {
        RedisSerializer<Object> serializer = serializer();
        CachedValue expected = value();
        List<CachedValue> list = new ArrayList<>(List.of(expected));
        CachedValue[] array = new CachedValue[] {expected};

        assertThat(serializer.deserialize(serializer.serialize(list)))
                .usingRecursiveComparison()
                .isEqualTo(list);
        assertThat(serializer.deserialize(serializer.serialize(array)))
                .usingRecursiveComparison()
                .isEqualTo(array);
    }

    @Test
    void preservesCurrentTypesAndEmptyValues() {
        RedisSerializer<Object> serializer = serializer();
        CachedValue expected = value();

        assertThat(serializer.deserialize(serializer.serialize(expected)))
                .usingRecursiveComparison()
                .isEqualTo(expected);
        assertThat(serializer.deserialize(serializer.serialize("value"))).isEqualTo("value");
        assertThat(serializer.deserialize(serializer.serialize(42))).isEqualTo(42);
        assertThat(serializer.deserialize(serializer.serialize(null))).isNull();
    }

    private CachedValue value() {
        CachedValue value = new CachedValue();
        value.text = "ucp-platform.business.text";
        value.updatedAt = LocalDateTime.of(2026, 10, 9, 12, 30);
        return value;
    }

    @SuppressWarnings("unchecked")
    private RedisSerializer<Object> serializer() {
        return (RedisSerializer<Object>) OsRedisAutoConfiguration.buildRedisSerializer();
    }
}
