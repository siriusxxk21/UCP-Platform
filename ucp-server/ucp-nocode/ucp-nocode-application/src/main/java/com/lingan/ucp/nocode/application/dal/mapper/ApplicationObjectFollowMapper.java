package com.lingan.ucp.nocode.application.dal.mapper;

import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.application.dal.dataobject.ApplicationObjectFollowDO;
import com.lingan.ucp.nocode.application.dal.dataobject.ApplicationObjectFollowLogDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 自动跟随的开关、状态与日志。状态行的写入同时负责在保存点回滚后清掉本会话的一级缓存。 */
@Mapper
public interface ApplicationObjectFollowMapper extends BaseMapperX<ApplicationObjectFollowDO> {
    ApplicationObjectFollowDO find(@Param("app") long app, @Param("object") long object);

    List<ApplicationObjectFollowDO> forApplication(@Param("app") long app);

    /** 拨开关，还没有行：插入并把修订号置为 1；已有行时不动并返回 0。 */
    int insertSwitch(
            @Param("app") long app,
            @Param("object") long object,
            @Param("enabled") boolean enabled,
            @Param("actor") String actor);

    /** 拨开关，已有行：按修订号更新；返回 0 表示修订号不符。 */
    int updateSwitch(
            @Param("app") long app,
            @Param("object") long object,
            @Param("enabled") boolean enabled,
            @Param("expected") int expected,
            @Param("actor") String actor);

    /** 记为已跟上：清掉待处理信息。开关与修订号不变。 */
    int followed(
            @Param("app") long app,
            @Param("object") long object,
            @Param("version") int version,
            @Param("actor") String actor);

    /** 记为待处理。开关与修订号不变。 */
    int pending(
            @Param("app") long app,
            @Param("object") long object,
            @Param("version") int version,
            @Param("code") String code,
            @Param("reason") String reason,
            @Param("actor") String actor);

    int log(@Param("row") ApplicationObjectFollowLogDO row, @Param("actor") String actor);

    /** 一次对象发布的跟随结果，按应用 ID 升序。 */
    List<ApplicationObjectFollowLogDO> byPlan(@Param("plan") String plan);

    /** 待处理的行，按 ID 升序，供定时重试分批处理。 */
    List<ApplicationObjectFollowDO> pendingRows(@Param("limit") int limit);

    /** 草稿引用了这个对象的启用中应用（含从没发布过的），按 ID 升序。 */
    List<Long> draftReferencing(@Param("object") String object);

    /** 某个对象上未完结的办理申请数（对象发布窗口的现状提示）。 */
    long unresolvedHandlingOfObject(
            @Param("object") long object, @Param("states") List<String> states);
}
