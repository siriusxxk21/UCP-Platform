package com.richuang.os.module.system.service.dept;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.module.system.dal.dataobject.dept.DeptDO;
import com.richuang.os.module.system.dal.dataobject.dept.UserDeptDO;
import com.richuang.os.module.system.dal.dataobject.user.AdminUserDO;
import com.richuang.os.module.system.dal.mysql.dept.UserDeptMapper;
import com.richuang.os.module.system.dal.mysql.user.AdminUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户部门关联核心业务规则单元测试。
 */
@ExtendWith(MockitoExtension.class)
class UserDeptServiceImplTest {

    @BeforeAll
    static void initializeMybatisMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), UserDeptDO.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), AdminUserDO.class);
    }

    @Mock
    private UserDeptMapper userDeptMapper;
    @Mock
    private AdminUserMapper userMapper;
    @Mock
    private DeptService deptService;
    @InjectMocks
    private UserDeptServiceImpl service;

    @Test
    void addSecondaryDeptShouldNotChangeUserMainDept() {
        when(deptService.getDept(20L)).thenReturn(dept(20L, 2L));
        when(userMapper.selectById(10L)).thenReturn(new AdminUserDO().setId(10L));
        when(userDeptMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        service.addUsers(20L, List.of(10L), "开发", false);

        verify(userDeptMapper).insert(any(UserDeptDO.class));
        verify(userMapper, never()).update(any(), any(Wrapper.class));
    }

    @Test
    void setMainDeptShouldUpdateRelationAndUserFields() {
        UserDeptDO relation = relation(100L, 10L, 20L, 0);
        when(userDeptMapper.selectById(100L)).thenReturn(relation);
        when(userDeptMapper.lockUser(10L)).thenReturn(10L);
        when(deptService.getDept(20L)).thenReturn(dept(20L, 2L));

        service.setMainDept(100L, 20L);

        verify(userDeptMapper).updateById(relation);
        verify(userMapper).update(any(), any(Wrapper.class));
    }

    @Test
    void removeMainDeptShouldBeRejected() {
        when(userDeptMapper.selectById(100L)).thenReturn(relation(100L, 10L, 20L, 1));

        assertThrows(ServiceException.class, () -> service.removeUser(100L));
        verify(userDeptMapper, never()).deleteById(100L);
    }

    @Test
    void syncMainDeptShouldMaintainRelationAndUserFields() {
        when(userDeptMapper.lockUser(10L)).thenReturn(10L);
        when(deptService.getDept(20L)).thenReturn(dept(20L, 2L));
        when(userDeptMapper.selectOne(any(Wrapper.class))).thenReturn(null);

        service.syncMainDept(10L, 20L);

        verify(userDeptMapper).insert(any(UserDeptDO.class));
        verify(userMapper).update(any(), any(Wrapper.class));
    }

    private static DeptDO dept(Long id, Long orgId) {
        return new DeptDO().setId(id).setOrgId(orgId);
    }

    private static UserDeptDO relation(Long id, Long userId, Long deptId, Integer isMain) {
        UserDeptDO relation = new UserDeptDO();
        relation.setId(id);
        relation.setUserId(userId);
        relation.setDeptId(deptId);
        relation.setIsMain(isMain);
        return relation;
    }
}
