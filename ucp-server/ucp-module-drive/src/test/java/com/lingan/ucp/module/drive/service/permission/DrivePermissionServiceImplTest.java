package com.lingan.ucp.module.drive.service.permission;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.framework.common.enums.CommonStatusEnum;
import com.lingan.ucp.module.drive.dal.dataobject.space.DriveSpaceDO;
import com.lingan.ucp.module.drive.dal.mysql.entry.DriveEntryMapper;
import com.lingan.ucp.module.drive.dal.mysql.permission.DrivePermissionMapper;
import com.lingan.ucp.module.drive.dal.mysql.share.DriveShareMapper;
import com.lingan.ucp.module.drive.dal.mysql.share.DriveShareSubjectMapper;
import com.lingan.ucp.module.drive.dal.mysql.space.DriveSpaceMapper;
import com.lingan.ucp.module.drive.enums.space.DriveSpaceTypeEnum;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 业务空间的内容访问不能被历史网盘 ACL 或分享放大。 */
class DrivePermissionServiceImplTest {

    @Test
    void ignoresOwnerAclAndShareForBusinessSpace() {
        DriveSpaceMapper spaceMapper = mock(DriveSpaceMapper.class);
        DriveEntryMapper entryMapper = mock(DriveEntryMapper.class);
        DrivePermissionMapper permissionMapper = mock(DrivePermissionMapper.class);
        DriveShareMapper shareMapper = mock(DriveShareMapper.class);
        DriveShareSubjectMapper shareSubjectMapper = mock(DriveShareSubjectMapper.class);
        DrivePermissionServiceImpl service = new DrivePermissionServiceImpl();
        ReflectionTestUtils.setField(service, "spaceMapper", spaceMapper);
        ReflectionTestUtils.setField(service, "entryMapper", entryMapper);
        ReflectionTestUtils.setField(service, "permissionMapper", permissionMapper);
        ReflectionTestUtils.setField(service, "shareMapper", shareMapper);
        ReflectionTestUtils.setField(service, "shareSubjectMapper", shareSubjectMapper);
        ReflectionTestUtils.setField(service, "permissionApi", mock(PermissionCommonApi.class));

        DriveSpaceDO biz =
                DriveSpaceDO.builder()
                        .id(10L)
                        .name("业务档案")
                        .type(DriveSpaceTypeEnum.BIZ.getCode())
                        .ownerId(7L)
                        .status(CommonStatusEnum.ENABLE.getStatus())
                        .build();
        when(spaceMapper.selectById(10L)).thenReturn(biz);

        assertNull(service.getEffectiveRole(10L, 0L, 7L));
        verify(entryMapper, never()).selectPathChain(any(), any());
        verify(permissionMapper, never()).selectListBySpaceAndEntryIds(any(), any());
        verify(shareMapper, never()).selectList(any());
        verify(shareSubjectMapper, never()).selectList(any());
    }
}
