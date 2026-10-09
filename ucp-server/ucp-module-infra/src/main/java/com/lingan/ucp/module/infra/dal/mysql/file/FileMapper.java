package com.lingan.ucp.module.infra.dal.mysql.file;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.lingan.ucp.module.infra.controller.admin.file.vo.file.FilePageReqVO;
import com.lingan.ucp.module.infra.dal.dataobject.file.FileDO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文件操作 Mapper
 *
 * @author os
 */
@Mapper
public interface FileMapper extends BaseMapperX<FileDO> {

    default PageResult<FileDO> selectPage(FilePageReqVO reqVO) {
        // 受保护文件由业务模块自行管理，不在通用文件列表中展示，避免泄露路径与访问地址
        return selectPage(reqVO, new LambdaQueryWrapperX<FileDO>()
                .eq(FileDO::getProtectedFlag, Boolean.FALSE)
                .likeIfPresent(FileDO::getPath, reqVO.getPath())
                .likeIfPresent(FileDO::getType, reqVO.getType())
                .betweenIfPresent(FileDO::getCreateTime, reqVO.getCreateTime())
                .orderByDesc(FileDO::getId));
    }

    /**
     * 判断同一存储路径下是否存在受保护文件
     *
     * 按“存在即拒绝”判断：即使有人另行登记了同路径的非受保护记录，也不能借此读取受保护内容。
     *
     * @param configId 配置编号
     * @param path     文件路径
     * @return 是否存在受保护文件
     */
    default boolean existsProtected(Long configId, String path) {
        return selectCount(new LambdaQueryWrapperX<FileDO>()
                .eq(FileDO::getConfigId, configId)
                .eq(FileDO::getPath, path)
                .eq(FileDO::getProtectedFlag, Boolean.TRUE)) > 0;
    }

}
