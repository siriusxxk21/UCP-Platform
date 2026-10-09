package com.lingan.ucp.nocode.application.service.resource;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.*;

import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 多个数据来源的维度对齐判定（契约第 5 章）。只读已解析的 (所属对象, 字段) 与字段配置，不查授权、不碰数据库，所以依赖扫描（不经 Spring 注入）也能直接用。判定次序：分组方式 →
 * 同一字段 → 引用同一对象 → 日期 → 同一套选项 → 同类原值 → 拦下。
 */
public final class ReportSourceAlignment {
    private ReportSourceAlignment() {}

    /** 对齐类别：键分别是 原值 / 目标记录 ID / 日期分桶文本 / 选项编码 / 原值文本。 */
    public enum Kind {
        SAME_FIELD,
        REFERENCE,
        DATE_BUCKET,
        OPTIONS,
        VALUE
    }

    private static final Set<FieldTypeEnum> RAW =
            Set.of(
                    FieldTypeEnum.TEXT,
                    FieldTypeEnum.AUTO_NUMBER,
                    FieldTypeEnum.INTEGER,
                    FieldTypeEnum.BOOLEAN);

    private static final Set<FieldTypeEnum> DECIMALS =
            Set.of(FieldTypeEnum.DECIMAL, FieldTypeEnum.MONEY, FieldTypeEnum.PERCENT);

    /** 一次判定的结果：kind 非空 = 相容；否则 reason 为 R1–R6 之一。 */
    record Check(Kind kind, String reason) {}

    /** 维度对齐：不能对齐时抛 L7 / L8。 */
    static Kind align(
            ApplicationReportValidator.Resolved main,
            ApplicationReports.Dimension mainDimension,
            ApplicationReportValidator.Resolved other,
            ApplicationReports.Dimension otherDimension,
            String source,
            String field,
            String dimension,
            Function<String, String> objectNames) {
        ReportBucketEnum mainBucket = ReportBucketEnum.fromCode(mainDimension.bucket());
        ReportBucketEnum otherBucket = ReportBucketEnum.fromCode(otherDimension.bucket());
        if (mainBucket != otherBucket)
            throw invalid(
                    ReportSourceMessages.bucket(source, field, dimension, bucketName(mainBucket)));
        Check check = compare(main, other, mainBucket, objectNames);
        if (check.kind() == null)
            throw invalid(ReportSourceMessages.align(source, field, dimension, check.reason()));
        return check.kind();
    }

    /** 筛选对应字段能否与来源 1 的同键字段用于同一个筛选（按值比较，判据同维度对齐）：能 ⇒ null，否则返回原因。 */
    static String filterReason(
            ApplicationReportValidator.Resolved main,
            ApplicationReportValidator.Resolved other,
            Function<String, String> objectNames) {
        return compare(main, other, ReportBucketEnum.VALUE, objectNames).reason();
    }

    static Check compare(
            ApplicationReportValidator.Resolved main,
            ApplicationReportValidator.Resolved other,
            ReportBucketEnum bucket,
            Function<String, String> objectNames) {
        FieldDefinition f1 = main.field(), fs = other.field();
        if (Objects.equals(main.owner().objectId(), other.owner().objectId())
                && Objects.equals(f1.id(), fs.id())) return new Check(Kind.SAME_FIELD, null);
        DataCenter.Relation r1 = BusinessFields.relation(main.owner(), f1.id());
        DataCenter.Relation rs = BusinessFields.relation(other.owner(), fs.id());
        boolean ref1 = r1 != null && !RelationTypeEnum.MANY_TO_MANY.matches(r1.kind());
        boolean refs = rs != null && !RelationTypeEnum.MANY_TO_MANY.matches(rs.kind());
        if (ref1 != refs) return new Check(null, ReportSourceMessages.R1);
        if (ref1) {
            if (!Objects.equals(r1.targetObjectId(), rs.targetObjectId()))
                return new Check(
                        null,
                        ReportSourceMessages.r2(
                                objectNames.apply(r1.targetObjectId()),
                                objectNames.apply(rs.targetObjectId())));
            return bucket == ReportBucketEnum.VALUE
                    ? new Check(Kind.REFERENCE, null)
                    : new Check(null, ReportSourceMessages.R6);
        }
        FieldTypeEnum t1 = FieldTypeEnum.fromCode(f1.type()),
                ts = FieldTypeEnum.fromCode(fs.type());
        if (date(t1) && date(ts)) {
            if (bucket != ReportBucketEnum.VALUE) return new Check(Kind.DATE_BUCKET, null);
            return t1 == FieldTypeEnum.DATE && ts == FieldTypeEnum.DATE
                    ? new Check(Kind.DATE_BUCKET, null)
                    : new Check(null, ReportSourceMessages.R3);
        }
        DataCenter.FieldOptions o1 = options(main.owner(), f1), os = options(other.owner(), fs);
        SelectionFields.Source s1 = SelectionFields.source(f1, o1);
        SelectionFields.Source ss = SelectionFields.source(fs, os);
        boolean sel1 = s1 != null && !SelectionSourceEnum.OBJECT_RELATION.matches(s1.kind());
        boolean sels = ss != null && !SelectionSourceEnum.OBJECT_RELATION.matches(ss.kind());
        if (sel1 || sels) {
            if (!sel1 || !sels) return new Check(null, ReportSourceMessages.R4);
            boolean local =
                    SelectionSourceEnum.LOCAL_OPTIONS.matches(s1.kind())
                            && SelectionSourceEnum.LOCAL_OPTIONS.matches(ss.kind());
            boolean same =
                    SelectionFields.identity(f1, o1).equals(SelectionFields.identity(fs, os))
                            && (!local
                                    || SelectionCompatibility.sameLocalOptions(
                                            o1.options(), os.options()));
            return same ? new Check(Kind.OPTIONS, null) : new Check(null, ReportSourceMessages.R4);
        }
        if (t1 == ts && RAW.contains(t1)) return new Check(Kind.VALUE, null);
        if (DECIMALS.contains(t1) || DECIMALS.contains(ts))
            return new Check(null, ReportSourceMessages.R5);
        return new Check(null, ReportSourceMessages.R6);
    }

    /** 契约第 6 章的 {分组}：按值、按日、按月、按年。 */
    static String bucketName(ReportBucketEnum bucket) {
        return switch (bucket) {
            case DAY -> "按日";
            case MONTH -> "按月";
            case YEAR -> "按年";
            default -> "按值";
        };
    }

    private static boolean date(FieldTypeEnum type) {
        return type == FieldTypeEnum.DATE || type == FieldTypeEnum.DATETIME;
    }

    private static DataCenter.FieldOptions options(DataCenter.Definition owner, FieldDefinition f) {
        return owner.fieldOptions() == null
                ? DataCenter.FieldOptions.defaults()
                : owner.fieldOptions().getOrDefault(f.id(), DataCenter.FieldOptions.defaults());
    }
}
