package com.richuang.os.module.system.dal.mysql.dict;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.richuang.os.module.system.controller.admin.dict.vo.type.DictTypePageReqVO;
import com.richuang.os.module.system.dal.dataobject.dict.DictTypeDO;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface DictTypeMapper extends BaseMapperX<DictTypeDO> {

    /** 字典维护是低频管理操作；事务锁串行化校验与写入，防止重复编码、重复值及删除后插入孤儿数据。 */
    @Select("SELECT pg_advisory_xact_lock(1002, 6)")
    @ResultType(Object.class)
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    void lockDictionaryWrites();

    default PageResult<DictTypeDO> selectPage(DictTypePageReqVO reqVO) {
        return selectPage(
                reqVO,
                new LambdaQueryWrapperX<DictTypeDO>()
                        .likeIfPresent(DictTypeDO::getName, reqVO.getName())
                        .likeIfPresent(DictTypeDO::getType, reqVO.getType())
                        .eqIfPresent(DictTypeDO::getStatus, reqVO.getStatus())
                        .betweenIfPresent(DictTypeDO::getCreateTime, reqVO.getCreateTime())
                        .orderByDesc(DictTypeDO::getId));
    }

    default DictTypeDO selectByType(String type) {
        return selectOne(DictTypeDO::getType, type);
    }

    default DictTypeDO selectByName(String name) {
        return selectOne(DictTypeDO::getName, name);
    }

    @Update(
            "UPDATE system_dict_type SET deleted = 1, deleted_time = #{deletedTime}, update_time ="
                    + " #{deletedTime}, updater = #{updater} WHERE id = #{id} AND deleted = 0")
    void updateToDelete(
            @Param("id") Long id,
            @Param("deletedTime") LocalDateTime deletedTime,
            @Param("updater") String updater);
}
