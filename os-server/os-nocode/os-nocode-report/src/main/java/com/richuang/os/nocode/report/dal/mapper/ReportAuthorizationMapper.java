package com.richuang.os.nocode.report.dal.mapper;

import com.richuang.os.nocode.report.dal.dataobject.*;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 授权读写均由数据集头锁保护；撤权不删修订号，不允许旧请求重放。 */
@Mapper
public interface ReportAuthorizationMapper {
    ReportResourceAclDO acl(@Param("kind") String kind, @Param("id") long id);

    ReportDatasetPolicyDO policy(@Param("id") long id);

    ReportObjectGrantDO ceiling(@Param("id") long id, @Param("object") long object);

    List<ReportObjectGrantDO> ceilings(@Param("id") long id);

    boolean referencesObject(@Param("id") long id, @Param("object") String object);

    /** 所有版本来源与保留的上限对象；移出草稿不能使旧授权失去撤销入口。 */
    List<String> authorizationObjectIds(@Param("id") long id);

    int saveAcl(
            @Param("kind") String kind,
            @Param("id") long id,
            @Param("json") String json,
            @Param("actor") String actor);

    int savePolicy(@Param("id") long id, @Param("json") String json, @Param("actor") String actor);

    int saveCeiling(
            @Param("id") long id,
            @Param("object") long object,
            @Param("json") String json,
            @Param("reason") String reason,
            @Param("actor") String actor);
}
