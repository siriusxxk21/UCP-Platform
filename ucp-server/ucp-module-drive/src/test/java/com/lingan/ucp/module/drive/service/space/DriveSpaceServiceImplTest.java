package com.lingan.ucp.module.drive.service.space;

import static com.lingan.ucp.module.drive.enums.ErrorCodeConstants.SPACE_NAME_DUPLICATE;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lingan.ucp.framework.common.enums.CommonStatusEnum;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.module.drive.controller.admin.space.vo.DriveBizSpaceCreateReqVO;
import com.lingan.ucp.module.drive.controller.admin.space.vo.DriveSpaceRespVO;
import com.lingan.ucp.module.drive.controller.admin.space.vo.DriveSpaceUpdateReqVO;
import com.lingan.ucp.module.drive.dal.dataobject.space.DriveSpaceDO;
import com.lingan.ucp.module.drive.dal.mysql.entry.DriveEntryMapper;
import com.lingan.ucp.module.drive.dal.mysql.space.DriveSpaceMapper;
import com.lingan.ucp.module.drive.enums.permission.DrivePermissionRoleEnum;
import com.lingan.ucp.module.drive.enums.space.DriveSpaceTypeEnum;
import com.lingan.ucp.module.drive.service.permission.DrivePermissionService;
import com.lingan.ucp.module.system.api.dept.DeptApi;
import com.lingan.ucp.module.system.api.user.AdminUserApi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 验证业务空间的治理边界，不把它混入普通网盘授权。 */
class DriveSpaceServiceImplTest {

    private static final long USER_ID = 7L;

    private final DriveSpaceMapper spaceMapper = mock(DriveSpaceMapper.class);
    private final DriveEntryMapper entryMapper = mock(DriveEntryMapper.class);
    private final DrivePermissionService permissionService = mock(DrivePermissionService.class);
    private final AdminUserApi adminUserApi = mock(AdminUserApi.class);
    private final DeptApi deptApi = mock(DeptApi.class);
    private final DriveSpaceServiceImpl service = new DriveSpaceServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "spaceMapper", spaceMapper);
        ReflectionTestUtils.setField(service, "entryMapper", entryMapper);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "adminUserApi", adminUserApi);
        ReflectionTestUtils.setField(service, "deptApi", deptApi);
    }

    @Test
    void createBusinessSpaceKeepsOwnerEmptyAndDoesNotCreateAcl() {
        DriveBizSpaceCreateReqVO request = new DriveBizSpaceCreateReqVO();
        request.setName("  业务档案  ");
        request.setQuotaBytes(1024L);
        when(spaceMapper.insert(any(DriveSpaceDO.class)))
                .thenAnswer(
                        invocation -> {
                            invocation.getArgument(0, DriveSpaceDO.class).setId(99L);
                            return 1;
                        });

        assertEquals(99L, service.createBizSpace(request));
        ArgumentCaptor<DriveSpaceDO> captor = ArgumentCaptor.forClass(DriveSpaceDO.class);
        verify(spaceMapper).insert(captor.capture());
        DriveSpaceDO created = captor.getValue();
        assertEquals("业务档案", created.getName());
        assertEquals(DriveSpaceTypeEnum.BIZ.getCode(), created.getType());
        assertNull(created.getOwnerId());
        assertNull(created.getOwnerDeptId());
        assertEquals(1024L, created.getQuotaBytes());
        assertEquals(CommonStatusEnum.ENABLE.getStatus(), created.getStatus());
        verify(permissionService, never()).getEffectiveRole(any(), any(), any());
    }

    @Test
    void createAndRenameBusinessSpaceReturnFriendlyDuplicateError() {
        DriveBizSpaceCreateReqVO create = new DriveBizSpaceCreateReqVO();
        create.setName("重名空间");
        when(spaceMapper.insert(any(DriveSpaceDO.class)))
                .thenThrow(new DuplicateKeyException("uk_drive_space_biz_name"));
        ServiceException createError =
                assertThrows(ServiceException.class, () -> service.createBizSpace(create));
        assertEquals(SPACE_NAME_DUPLICATE.getCode(), createError.getCode());

        DriveSpaceDO biz = space(11L, "原名", DriveSpaceTypeEnum.BIZ.getCode(), null);
        when(spaceMapper.selectById(11L)).thenReturn(biz);
        when(spaceMapper.updateById(any(DriveSpaceDO.class)))
                .thenThrow(new DuplicateKeyException("uk_drive_space_biz_name"));
        DriveSpaceUpdateReqVO update = new DriveSpaceUpdateReqVO();
        update.setId(11L);
        update.setName("重名空间");
        ServiceException updateError =
                assertThrows(
                        ServiceException.class, () -> service.updateTeamSpace(update, USER_ID));
        assertEquals(SPACE_NAME_DUPLICATE.getCode(), updateError.getCode());
    }

    @Test
    void myListExcludesBusinessSpaceAndManageListOnlyAddsManagersAndBusinessSpaces() {
        DriveSpaceDO personal = space(1L, "我的空间", DriveSpaceTypeEnum.PERSONAL.getCode(), USER_ID);
        DriveSpaceDO managed = space(2L, "管理团队", DriveSpaceTypeEnum.TEAM.getCode(), USER_ID);
        DriveSpaceDO viewed = space(3L, "查看团队", DriveSpaceTypeEnum.TEAM.getCode(), 8L);
        DriveSpaceDO biz = space(4L, "业务档案", DriveSpaceTypeEnum.BIZ.getCode(), null);
        when(spaceMapper.selectByOwnerIdAndType(USER_ID, DriveSpaceTypeEnum.PERSONAL.getCode()))
                .thenReturn(personal);
        when(permissionService.getAccessibleSpaceIds(USER_ID)).thenReturn(Set.of(1L, 2L, 3L, 4L));
        when(spaceMapper.selectListByIds(any()))
                .thenReturn(List.of(personal, managed, viewed, biz));
        when(adminUserApi.getUserMap(any())).thenReturn(Map.of());
        when(deptApi.getDeptMap(any())).thenReturn(Map.of());
        when(permissionService.getEffectiveRole(1L, 0L, USER_ID))
                .thenReturn(DrivePermissionRoleEnum.MANAGER);
        when(permissionService.getEffectiveRole(2L, 0L, USER_ID))
                .thenReturn(DrivePermissionRoleEnum.MANAGER);
        when(permissionService.getEffectiveRole(3L, 0L, USER_ID))
                .thenReturn(DrivePermissionRoleEnum.VIEWER);
        when(spaceMapper.selectBizList()).thenReturn(List.of(biz));

        List<DriveSpaceRespVO> myList = service.getMySpaceList(USER_ID);
        assertEquals(List.of(1L, 3L, 2L), myList.stream().map(DriveSpaceRespVO::getId).toList());

        List<DriveSpaceRespVO> manageList = service.getManageSpaceList(USER_ID);
        assertEquals(
                List.of(1L, 2L, 4L), manageList.stream().map(DriveSpaceRespVO::getId).toList());
        DriveSpaceRespVO bizVO = manageList.get(2);
        assertNull(bizVO.getOwnerId());
        assertNull(bizVO.getRole());
    }

    private static DriveSpaceDO space(Long id, String name, String type, Long ownerId) {
        return DriveSpaceDO.builder()
                .id(id)
                .name(name)
                .type(type)
                .ownerId(ownerId)
                .quotaBytes(0L)
                .usedBytes(0L)
                .status(CommonStatusEnum.ENABLE.getStatus())
                .build();
    }
}
