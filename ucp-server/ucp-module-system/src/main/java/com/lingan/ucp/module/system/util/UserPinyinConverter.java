package com.lingan.ucp.module.system.util;

import org.dromara.hutool.extra.pinyin.PinyinUtil;
import org.dromara.hutool.extra.pinyin.engine.PinyinEngine;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * 用户昵称拼音转换器。
 *
 * <p>统一生成无声调、小写且不带分隔符的全拼和首拼，确保新增、修改、导入及历史回填使用同一套规则。</p>
 */
@Component
public class UserPinyinConverter {

    private final PinyinEngine engine = PinyinUtil.createEngine("Pinyin4j");

    /**
     * 将昵称转换为持久化所需的全拼和首拼。
     *
     * @param nickname 用户昵称
     * @return 转换结果；空昵称对应两个空字符串
     */
    public UserPinyin convert(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return new UserPinyin("", "");
        }
        String fullPinyin = engine.getPinyin(nickname, "").toLowerCase(Locale.ROOT);
        String initials = engine.getFirstLetter(nickname, "").toLowerCase(Locale.ROOT);
        return new UserPinyin(fullPinyin, initials);
    }

    /** 用户昵称拼音转换结果。 */
    public record UserPinyin(String fullPinyin, String initials) {
    }
}
