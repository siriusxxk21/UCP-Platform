package com.lingan.ucp.framework.redis.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 无需连接 Redis，验证包迁移前后的真实 JSON 缓存协议。 */
class OsRedisAutoConfigurationTest {

    // 模拟线上旧缓存中的类型标识和同名业务文本。
    private static final String OLD_PACKAGE = "com.richuang.os.";

    public static class CachedValue {
        public String text;
        public LocalDateTime updatedAt;
    }

    @Test
    void readsLegacyTypeWithoutRewritingBusinessText() {
        RedisSerializer<Object> serializer = serializer();
        CachedValue expected = value();
        byte[] historical = historical(serializer.serialize(expected));

        Object restored = serializer.deserialize(historical);

        assertThat(restored).isInstanceOf(CachedValue.class);
        assertThat(restored).usingRecursiveComparison().isEqualTo(expected);
        assertThat(new String(serializer.serialize(restored), StandardCharsets.UTF_8))
                .contains(CachedValue.class.getName())
                .contains(expected.text)
                .doesNotContain(oldClassName());
    }

    @Test
    void readsLegacyTypesNestedInCollectionsAndArrays() {
        RedisSerializer<Object> serializer = serializer();
        CachedValue expected = value();
        List<CachedValue> list = new ArrayList<>(List.of(expected));
        CachedValue[] array = new CachedValue[] {expected};

        assertThat(serializer.deserialize(historical(serializer.serialize(list))))
                .usingRecursiveComparison()
                .isEqualTo(list);
        assertThat(serializer.deserialize(historical(serializer.serialize(array))))
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
        value.text = OLD_PACKAGE + "business.text";
        value.updatedAt = LocalDateTime.of(2026, 10, 9, 12, 30);
        return value;
    }

    private byte[] historical(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8)
                .replace(CachedValue.class.getName(), oldClassName())
                .getBytes(StandardCharsets.UTF_8);
    }

    private String oldClassName() {
        return OLD_PACKAGE + CachedValue.class.getName().substring("com.lingan.ucp.".length());
    }

    @SuppressWarnings("unchecked")
    private RedisSerializer<Object> serializer() {
        return (RedisSerializer<Object>) OsRedisAutoConfiguration.buildRedisSerializer();
    }
}
