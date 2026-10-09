package com.lingan.ucp.module.drive.service.share;

import static com.lingan.ucp.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.lingan.ucp.module.drive.enums.ErrorCodeConstants.PERMISSION_DENIED;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.module.drive.controller.admin.share.vo.DriveShareUpdateReqVO;
import com.lingan.ucp.module.drive.dal.dataobject.share.DriveShareDO;
import com.lingan.ucp.module.drive.dal.mysql.share.DriveShareMapper;
import com.lingan.ucp.module.drive.enums.permission.DrivePermissionRoleEnum;
import com.lingan.ucp.module.drive.service.permission.DrivePermissionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 分享创建者的历史身份只能用于撤回，不能代替当前管理权限继续授权。 */
class DriveShareServiceImplTest {
    private final DriveShareMapper mapper = mock(DriveShareMapper.class);
    private final DrivePermissionService permissionService = mock(DrivePermissionService.class);
    private final DriveShareServiceImpl service = new DriveShareServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "shareMapper", mapper);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        DriveShareDO share =
                DriveShareDO.builder()
                        .id(1L)
                        .spaceId(2L)
                        .entryId(3L)
                        .status("ACTIVE")
                        .role("VIEWER")
                        .build();
        share.setCreator("7");
        when(mapper.selectById(1L)).thenReturn(share);
    }

    @Test
    void formerManagerCannotUpgradeOrExtendOwnShare() {
        doThrow(exception(PERMISSION_DENIED))
                .when(permissionService)
                .validatePermission(2L, 3L, 7L, DrivePermissionRoleEnum.MANAGER);
        assertThrows(ServiceException.class, () -> service.updateShare(request(), 7L));
        verify(mapper, never()).updateRoleAndExpireTime(any(), any(), any());
    }

    @Test
    void currentManagerCanUpdateShare() {
        service.updateShare(request(), 7L);
        verify(permissionService).validatePermission(2L, 3L, 7L, DrivePermissionRoleEnum.MANAGER);
        verify(mapper).updateRoleAndExpireTime(1L, "EDITOR", null);
    }

    @Test
    void formerManagerCanStillRevokeOwnShare() {
        service.revokeShare(1L, 7L);
        verify(mapper).updateById(any(DriveShareDO.class));
        verifyNoInteractions(permissionService);
    }

    private DriveShareUpdateReqVO request() {
        DriveShareUpdateReqVO request = new DriveShareUpdateReqVO();
        request.setId(1L);
        request.setRole("EDITOR");
        return request;
    }
}
