package com.lingan.ucp.module.drive.service.entry;

import static com.lingan.ucp.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.lingan.ucp.module.drive.enums.ErrorCodeConstants.PERMISSION_DENIED;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryCopyReqVO;
import com.lingan.ucp.module.drive.controller.admin.entry.vo.DriveEntryListReqVO;
import com.lingan.ucp.module.drive.dal.dataobject.entry.DriveEntryDO;
import com.lingan.ucp.module.drive.dal.mysql.entry.DriveEntryMapper;
import com.lingan.ucp.module.drive.dal.mysql.space.DriveSpaceMapper;
import com.lingan.ucp.module.drive.enums.permission.DrivePermissionRoleEnum;
import com.lingan.ucp.module.drive.service.permission.DrivePermissionService;
import com.lingan.ucp.module.drive.service.space.DriveSpaceService;
import com.lingan.ucp.module.drive.service.storage.DriveStorageService;
import com.lingan.ucp.module.infra.api.file.FileApi;
import com.lingan.ucp.module.infra.dal.dataobject.file.FileDO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** 覆盖目录继承边界与移动过的旧文件复制，防止列表泄露和整树复制丢失内容。 */
class DriveEntryServiceImplTest {
    private final DriveEntryMapper entryMapper = mock(DriveEntryMapper.class);
    private final DrivePermissionService permissionService = mock(DrivePermissionService.class);
    private final FileApi fileApi = mock(FileApi.class);
    private final DriveSpaceMapper spaceMapper = mock(DriveSpaceMapper.class);
    private final DriveEntryServiceImpl service = new DriveEntryServiceImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "entryMapper", entryMapper);
        ReflectionTestUtils.setField(service, "permissionService", permissionService);
        ReflectionTestUtils.setField(service, "fileApi", fileApi);
        ReflectionTestUtils.setField(service, "spaceMapper", spaceMapper);
        ReflectionTestUtils.setField(service, "spaceService", mock(DriveSpaceService.class));
        DriveStorageService storageService = mock(DriveStorageService.class);
        when(storageService.getSelectedConfigId()).thenReturn(null);
        ReflectionTestUtils.setField(service, "storageService", storageService);
    }

    @Test
    void listingDoesNotExposeChildOutsideInheritedPermission() {
        DriveEntryDO visible = node(10L, 0L, "FOLDER");
        DriveEntryDO hidden = node(11L, 0L, "FILE");
        when(entryMapper.selectListByParent(1L, 0L)).thenReturn(List.of(visible, hidden));
        when(permissionService.getEffectiveRole(1L, 10L, 7L))
                .thenReturn(DrivePermissionRoleEnum.VIEWER);
        DriveEntryListReqVO request = new DriveEntryListReqVO();
        request.setSpaceId(1L);
        request.setParentId(0L);

        assertEquals(List.of(visible), service.getEntryList(request, 7L));
    }

    @Test
    void copyRejectsUnreadableDescendantBeforeAnyStorageWrite() {
        prepareCopy();
        doThrow(exception(PERMISSION_DENIED))
                .when(permissionService)
                .validatePermission(1L, 10L, 7L, DrivePermissionRoleEnum.VIEWER);

        assertThrows(ServiceException.class, () -> service.copyEntry(copyRequest(), 7L));
        verify(entryMapper, never()).insert(any(DriveEntryDO.class));
        verifyNoInteractions(fileApi);
    }

    @Test
    void copyPreservesOlderFileMovedIntoNewerFolder() {
        prepareCopy();
        AtomicLong sequence = new AtomicLong(100L);
        doAnswer(
                        invocation -> {
                            DriveEntryDO entry = invocation.getArgument(0);
                            entry.setId(sequence.incrementAndGet());
                            return 1;
                        })
                .when(entryMapper)
                .insert(any(DriveEntryDO.class));
        when(fileApi.getFileContent(40L)).thenReturn(new byte[] {1, 2, 3});
        when(fileApi.createProtectedFile(any(byte[].class), anyString(), anyString(), anyString()))
                .thenReturn(new FileDO().setId(41L).setSize(3L));

        assertEquals(101L, service.copyEntry(copyRequest(), 7L));
        ArgumentCaptor<DriveEntryDO> copied = ArgumentCaptor.forClass(DriveEntryDO.class);
        verify(entryMapper, times(2)).insert(copied.capture());
        assertEquals(101L, copied.getAllValues().get(1).getParentId());
        assertEquals(41L, copied.getAllValues().get(1).getFileId());
        verify(spaceMapper).updateUsedBytes(2L, 3L);
    }

    private void prepareCopy() {
        DriveEntryDO root = node(20L, 0L, "FOLDER");
        DriveEntryDO olderChild = node(10L, 20L, "FILE");
        olderChild.setFileId(40L);
        when(entryMapper.selectById(20L)).thenReturn(root);
        // Mapper 按真实层级排序；编号先后不能代表父子关系。
        when(entryMapper.selectSubtree(1L, 20L))
                .thenReturn(new ArrayList<>(List.of(root, olderChild)));
    }

    private DriveEntryCopyReqVO copyRequest() {
        DriveEntryCopyReqVO request = new DriveEntryCopyReqVO();
        request.setId(20L);
        request.setTargetSpaceId(2L);
        request.setTargetParentId(0L);
        return request;
    }

    private DriveEntryDO node(Long id, Long parentId, String type) {
        return DriveEntryDO.builder()
                .id(id)
                .spaceId(1L)
                .parentId(parentId)
                .name("文件" + id)
                .type(type)
                .size(3L)
                .mimeType("text/plain")
                .trashState("NORMAL")
                .inheritParent(true)
                .build();
    }
}
