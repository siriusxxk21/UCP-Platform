package com.richuang.os.framework.job.core.util;

import org.springframework.scheduling.support.CronExpression;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * PowerJob Cron 表达式工具类。
 *
 * <p>PowerJob 使用六段式 Cron。为兼容历史 Quartz 数据，允许第七段为通配符 {@code *}，
 * 在传递给 PowerJob 前会去除该年份段。</p>
 */
public final class CronUtils {

    private CronUtils() {
    }

    public static boolean isValid(String cronExpression) {
        try {
            CronExpression.parse(normalize(cronExpression));
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    public static String normalize(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            throw new IllegalArgumentException("Cron 表达式不能为空");
        }
        String[] fields = cronExpression.trim().split("\\s+");
        if (fields.length == 6) {
            return String.join(" ", fields);
        }
        if (fields.length == 7 && "*".equals(fields[6])) {
            return String.join(" ", Arrays.copyOf(fields, 6));
        }
        throw new IllegalArgumentException("PowerJob 仅支持六段式 Cron，历史七段式的年份必须为 *");
    }

    public static List<LocalDateTime> getNextTimes(String cronExpression, int count) {
        CronExpression cron = CronExpression.parse(normalize(cronExpression));
        List<LocalDateTime> nextTimes = new ArrayList<>(Math.max(count, 0));
        LocalDateTime current = LocalDateTime.now();
        for (int i = 0; i < count; i++) {
            current = cron.next(current);
            if (current == null) {
                break;
            }
            nextTimes.add(current);
        }
        return nextTimes;
    }
}
