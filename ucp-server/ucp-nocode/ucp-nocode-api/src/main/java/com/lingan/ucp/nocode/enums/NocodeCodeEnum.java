package com.lingan.ucp.nocode.enums;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 数据中心枚举的稳定编码契约。接口与数据库保存编码，业务判断统一使用枚举。 */
public interface NocodeCodeEnum {
    String getCode();

    /** 允许对可空的旧数据进行比较；不把未知编码当成默认状态。 */
    default boolean matches(String code) {
        return getCode().equals(code);
    }

    static <E extends Enum<E> & NocodeCodeEnum> E require(Class<E> type, String code) {
        return Arrays.stream(type.getEnumConstants())
                .filter(value -> value.matches(code))
                .findFirst()
                .orElseThrow(() -> invalid("无效的 " + type.getSimpleName() + " 编码：" + code));
    }

    static <E extends Enum<E> & NocodeCodeEnum> Set<String> codes(Class<E> type) {
        return Arrays.stream(type.getEnumConstants())
                .map(NocodeCodeEnum::getCode)
                .collect(Collectors.toUnmodifiableSet());
    }
}
