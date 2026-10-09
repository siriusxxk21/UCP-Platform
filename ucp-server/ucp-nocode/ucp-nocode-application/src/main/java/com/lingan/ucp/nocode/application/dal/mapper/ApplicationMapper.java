package com.lingan.ucp.nocode.application.dal.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.nocode.application.dal.dataobject.*;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 应用元数据沿用底座分页、MyBatis 会话和事务。JSON 写入显式转换，外部值均参数绑定。 */
@Mapper
public interface ApplicationMapper extends BaseMapperX<NocodeApplicationDO> {
    /** 已启用且存在发布版本的应用，保持名称与 ID 的既有排序。 */
    List<String> runnableIds();

    /** 自动化目录锁先于应用、对象和业务记录锁；读写发布指针不能穿透同一业务事务。 */
    void automationCatalogLock(@Param("exclusive") boolean exclusive);

    /** 已启用发布配置，不获取应用头行锁；目录事务锁负责快照稳定性。 */
    List<String> automationSnapshots();

    IPage<NocodeApplicationDO> page(
            IPage<NocodeApplicationDO> page,
            @Param("search") String search,
            @Param("creator") String creator,
            @Param("category") String category);

    List<String> categories(@Param("creator") String creator);

    IPage<NocodeApplicationDO> recyclePage(
            IPage<NocodeApplicationDO> page,
            @Param("search") String search,
            @Param("creator") String creator);

    NocodeApplicationDO lockDeleted(@Param("id") long id);

    int recycle(@Param("id") long id, @Param("reason") String reason, @Param("actor") String actor);

    int restoreDeleted(
            @Param("id") long id, @Param("reason") String reason, @Param("actor") String actor);

    int completeRecovery(@Param("id") long id, @Param("actor") String actor);

    long unresolvedHandling(@Param("id") long id, @Param("states") List<String> states);

    NocodeApplicationDO lock(@Param("id") long id, @Param("write") boolean write);

    int create(@Param("app") NocodeApplicationDO app, @Param("actor") String actor);

    int updateDraft(
            @Param("app") NocodeApplicationDO app,
            @Param("expected") int expected,
            @Param("actor") String actor);

    List<NocodeApplicationVersionDO> versions(@Param("id") long id);

    /** 发布记录按版本号倒序分页；列表投影不加载版本定义 JSON。 */
    IPage<NocodeApplicationVersionDO> versionPage(
            IPage<NocodeApplicationVersionDO> page, @Param("id") long id);

    NocodeApplicationVersionDO version(@Param("id") long id, @Param("number") int number);

    int createVersion(
            @Param("version") NocodeApplicationVersionDO version, @Param("actor") String actor);

    int publish(@Param("id") long id, @Param("number") int number, @Param("actor") String actor);

    int status(@Param("id") long id, @Param("status") String status, @Param("actor") String actor);
}
