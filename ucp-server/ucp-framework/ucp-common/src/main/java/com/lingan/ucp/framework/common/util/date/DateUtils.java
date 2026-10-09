package com.lingan.ucp.framework.common.util.date;

import cn.hutool.core.date.LocalDateTimeUtil;

import java.time.*;
import java.util.Calendar;
import java.util.Date;
import java.util.TimeZone;

/**
 * 时间工具类
 *
 * @author os
 */
public class DateUtils {

    /**
     * 时区 - 默认（固定 Asia/Shanghai，避免线上服务器 JVM 默认时区不一致导致日期偏差）
     */
    public static final String TIME_ZONE_DEFAULT = "Asia/Shanghai";

    /**
     * 固定业务时区实例，全局复用
     */
    public static final ZoneId ZONE_ID = ZoneId.of(TIME_ZONE_DEFAULT);

    /**
     * 秒转换成毫秒
     */
    public static final long SECOND_MILLIS = 1000;

    public static final String FORMAT_YEAR_MONTH_DAY = "yyyy-MM-dd";

    public static final String FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND = "yyyy-MM-dd HH:mm:ss";

    /**
     * 将 LocalDateTime 转换成 Date（使用固定 Asia/Shanghai 时区）
     *
     * @param date LocalDateTime
     * @return Date
     */
    public static Date of(LocalDateTime date) {
        if (date == null) {
            return null;
        }
        // 使用固定 Asia/Shanghai 时区，避免线上 UTC 服务器出现时区偏差
        ZonedDateTime zonedDateTime = date.atZone(ZONE_ID);
        Instant instant = zonedDateTime.toInstant();
        return Date.from(instant);
    }

    /**
     * 将 Date 转换成 LocalDateTime（使用固定 Asia/Shanghai 时区）
     *
     * @param date Date
     * @return LocalDateTime
     */
    public static LocalDateTime of(Date date) {
        if (date == null) {
            return null;
        }
        // 使用固定 Asia/Shanghai 时区，避免线上 UTC 服务器出现时区偏差
        Instant instant = date.toInstant();
        return LocalDateTime.ofInstant(instant, ZONE_ID);
    }

    public static Date addTime(Duration duration) {
        return new Date(System.currentTimeMillis() + duration.toMillis());
    }

    public static boolean isExpired(LocalDateTime time) {
        LocalDateTime now = LocalDateTime.now();
        return now.isAfter(time);
    }

    /**
     * 创建指定时间（使用固定 Asia/Shanghai 时区）
     *
     * @param year  年
     * @param month 月
     * @param day   日
     * @return 指定时间
     */
    public static Date buildTime(int year, int month, int day) {
        return buildTime(year, month, day, 0, 0, 0);
    }

    /**
     * 创建指定时间（使用固定 Asia/Shanghai 时区）
     *
     * @param year   年
     * @param month  月
     * @param day    日
     * @param hour   小时
     * @param minute 分钟
     * @param second 秒
     * @return 指定时间
     */
    public static Date buildTime(int year, int month, int day,
                                 int hour, int minute, int second) {
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone(ZONE_ID));
        calendar.set(Calendar.YEAR, year);
        calendar.set(Calendar.MONTH, month - 1);
        calendar.set(Calendar.DAY_OF_MONTH, day);
        calendar.set(Calendar.HOUR_OF_DAY, hour);
        calendar.set(Calendar.MINUTE, minute);
        calendar.set(Calendar.SECOND, second);
        calendar.set(Calendar.MILLISECOND, 0); // 一般情况下，都是 0 毫秒
        return calendar.getTime();
    }

    public static Date max(Date a, Date b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareTo(b) > 0 ? a : b;
    }

    public static LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    /**
     * 是否今天
     *
     * @param date 日期
     * @return 是否
     */
    public static boolean isToday(LocalDateTime date) {
        return LocalDateTimeUtil.isSameDay(date, LocalDateTime.now());
    }

    /**
     * 是否昨天
     *
     * @param date 日期
     * @return 是否
     */
    public static boolean isYesterday(LocalDateTime date) {
        return LocalDateTimeUtil.isSameDay(date, LocalDateTime.now().minusDays(1));
    }

    /**
     * 将 java.util.Date 转换为 LocalDate（使用固定 Asia/Shanghai 时区）
     * 作为 Date → LocalDate 转换的唯一入口，避免各 Service 直接使用 ZoneId.systemDefault()
     *
     * @param date Date 对象
     * @return LocalDate，若 date 为 null 则返回 null
     */
    public static LocalDate toLocalDate(Date date) {
        if (date == null) {
            return null;
        }
        return date.toInstant().atZone(ZONE_ID).toLocalDate();
    }

    /**
     * 获取今天的日期（使用固定 Asia/Shanghai 时区）
     * 替代裸调 LocalDate.now()，避免 JVM 时区为 UTC 时获取到错误的"今天"
     *
     * @return 今天的 LocalDate（Asia/Shanghai 时区）
     */
    public static LocalDate today() {
        return LocalDate.now(ZONE_ID);
    }

}
