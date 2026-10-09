package com.richuang.os.module.system.dal.mysql.dept;

import com.richuang.os.framework.mybatis.core.mapper.BaseMapperX;
import com.richuang.os.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.richuang.os.module.system.controller.admin.dept.vo.dept.DeptListReqVO;
import com.richuang.os.module.system.dal.dataobject.dept.DeptDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;

@Mapper
public interface DeptMapper extends BaseMapperX<DeptDO> {

    /**
     * 查询组织下包含已删除记录在内的历史编码，保证序号永不复用。
     */
    @Select("SELECT dept_code FROM system_dept WHERE org_id = #{orgId} AND dept_code IS NOT NULL")
    List<String> selectHistoricalCodesByOrgId(Long orgId);

    default List<DeptDO> selectListAll() {
        return selectList(new LambdaQueryWrapperX<DeptDO>()
                .orderByAsc(DeptDO::getSort)
                .orderByAsc(DeptDO::getId));
    }

    default List<DeptDO> selectListByTenantId(Long tenantId) {
        return selectList(new LambdaQueryWrapperX<DeptDO>()
                .eq(DeptDO::getTenantId, tenantId)
                .orderByAsc(DeptDO::getSort)
                .orderByAsc(DeptDO::getId));
    }

    default List<DeptDO> selectListByCodes(Collection<String> codes) {
        return selectList(new LambdaQueryWrapperX<DeptDO>()
                .in(DeptDO::getDeptCode, codes));
    }

    default List<DeptDO> selectList(DeptListReqVO reqVO) {
        return selectList(new LambdaQueryWrapperX<DeptDO>()
                .like(StringUtils.hasText(reqVO.getName()), DeptDO::getName, reqVO.getName())
                // 状态值包含 0，显式按 null 判断，确保“启用”条件也会参与数据库查询。
                .eq(reqVO.getStatus() != null, DeptDO::getStatus, reqVO.getStatus())
                .eq(reqVO.getOrgId() != null, DeptDO::getOrgId, reqVO.getOrgId()));
    }

    default DeptDO selectByParentIdAndName(Long parentId, String name) {
        return selectOne(DeptDO::getParentId, parentId, DeptDO::getName, name);
    }

    default DeptDO selectByOrgIdAndCode(Long orgId, String code) {
        return selectOne(DeptDO::getOrgId, orgId, DeptDO::getDeptCode, code);
    }

    default Long selectCountByParentId(Long parentId) {
        return selectCount(DeptDO::getParentId, parentId);
    }

    default List<DeptDO> selectListByParentId(Collection<Long> parentIds) {
        return selectList(DeptDO::getParentId, parentIds);
    }

    default List<DeptDO> selectListByLeaderUserId(Long id) {
        return selectList(DeptDO::getLeaderUserId, id);
    }

}
