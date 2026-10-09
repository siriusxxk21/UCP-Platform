package com.lingan.ucp.module.drive.service.bizfile;

import static com.lingan.ucp.module.drive.enums.ErrorCodeConstants.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lingan.ucp.framework.common.exception.ErrorCode;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.module.drive.api.bizfile.dto.DriveBizFileContent;
import com.lingan.ucp.module.drive.api.bizfile.dto.DriveBizTemporaryContent;
import com.lingan.ucp.module.drive.dal.dataobject.entry.DriveEntryDO;
import com.lingan.ucp.module.drive.dal.dataobject.space.DriveSpaceDO;
import com.lingan.ucp.module.drive.dal.mysql.entry.DriveEntryMapper;
import com.lingan.ucp.module.drive.dal.mysql.space.DriveSpaceMapper;
import com.lingan.ucp.module.drive.enums.entry.DriveEntryTypeEnum;
import com.lingan.ucp.module.drive.enums.space.DriveSpaceTypeEnum;
import com.lingan.ucp.module.infra.api.file.FileApi;
import com.lingan.ucp.module.infra.dal.dataobject.file.FileDO;
import com.lingan.ucp.module.infra.service.file.FileService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 受管业务文件内容读取原语与临时上传内容原语：节点身份（受管、文件、业务空间）在每次调用时重验； 内容长度与内容流都经文件底座按偏移读取，内容缺失时拒绝而不是返回空流；
 * 临时内容只接受带受保护标记的文件，普通附件按不存在处理。
 */
class DriveBizFileApiImplTest {

    private static final long ENTRY_ID = 930001L;
    private static final long SPACE_ID = 910001L;
    private static final long FILE_ID = 1001L;

    private final DriveEntryMapper entryMapper = mock(DriveEntryMapper.class);
    private final DriveSpaceMapper spaceMapper = mock(DriveSpaceMapper.class);
    private final FileApi fileApi = mock(FileApi.class);
    private final FileService fileService = mock(FileService.class);
    private final DriveBizFileApiImpl api = new DriveBizFileApiImpl();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(api, "entryMapper", entryMapper);
        ReflectionTestUtils.setField(api, "spaceMapper", spaceMapper);
        ReflectionTestUtils.setField(api, "fileApi", fileApi);
        ReflectionTestUtils.setField(api, "fileService", fileService);
        when(spaceMapper.selectById(SPACE_ID)).thenReturn(bizSpace());
        when(entryMapper.selectById(ENTRY_ID)).thenReturn(managedFile());
    }

    @Test
    void readsManagedFileContentWithStorageLengthAndOffset() {
        when(fileApi.getFileContentLength(FILE_ID)).thenReturn(2048L);
        DriveBizFileContent content = api.contentInfo(ENTRY_ID);
        assertThat(content.entryId()).isEqualTo(ENTRY_ID);
        assertThat(content.spaceId()).isEqualTo(SPACE_ID);
        assertThat(content.fileId()).isEqualTo(FILE_ID);
        assertThat(content.name()).isEqualTo("合同扫描件.pdf");
        assertThat(content.size()).isEqualTo(2048L);
        assertThat(content.mimeType()).isEqualTo("application/pdf");
        assertThat(content.length()).isEqualTo(2048L);

        InputStream stream = new ByteArrayInputStream("contract".getBytes(StandardCharsets.UTF_8));
        when(fileApi.getFileContentStream(FILE_ID, 1024L)).thenReturn(stream);
        assertThat(api.openContent(ENTRY_ID, 1024L)).isSameAs(stream);
        verify(fileApi).getFileContentStream(FILE_ID, 1024L);
    }

    @Test
    void rejectsNodesOutsideManagedBusinessFileIdentity() {
        assertCode(ENTRY_NOT_EXISTS, () -> api.contentInfo(999999L));
        assertCode(ENTRY_NOT_EXISTS, () -> api.openContent(999999L, 0L));

        // 目录节点不能作为文件内容读取
        DriveEntryDO folder = managedFile();
        folder.setType(DriveEntryTypeEnum.FOLDER.getCode());
        when(entryMapper.selectById(ENTRY_ID)).thenReturn(folder);
        assertCode(ENTRY_MANAGED_FORBIDDEN, () -> api.contentInfo(ENTRY_ID));

        // 普通网盘文件不能借业务读取原语读出
        DriveEntryDO plain = managedFile();
        plain.setManagedBiz(Boolean.FALSE);
        when(entryMapper.selectById(ENTRY_ID)).thenReturn(plain);
        assertCode(ENTRY_MANAGED_FORBIDDEN, () -> api.openContent(ENTRY_ID, 0L));

        // 非业务空间的受管节点同样拒绝
        DriveSpaceDO personal = bizSpace();
        personal.setType(DriveSpaceTypeEnum.PERSONAL.getCode());
        when(entryMapper.selectById(ENTRY_ID)).thenReturn(managedFile());
        when(spaceMapper.selectById(SPACE_ID)).thenReturn(personal);
        assertCode(SPACE_BIZ_FORBIDDEN, () -> api.contentInfo(ENTRY_ID));
        assertCode(SPACE_BIZ_FORBIDDEN, () -> api.openContent(ENTRY_ID, 0L));
    }

    @Test
    void refusesMissingContentInsteadOfReturningEmptyStream() {
        when(fileApi.getFileContentStream(FILE_ID, 0L)).thenReturn(null);
        assertCode(ENTRY_FILE_CONTENT_MISSING, () -> api.openContent(ENTRY_ID, 0L));
    }

    @Test
    void readsTemporaryContentOnlyForProtectedFiles() {
        when(fileService.getFile(FILE_ID)).thenReturn(protectedFile());
        when(fileApi.getFileContentLength(FILE_ID)).thenReturn(2048L);

        DriveBizTemporaryContent content = api.temporaryContentInfo(FILE_ID);
        assertThat(content.fileId()).isEqualTo(FILE_ID);
        assertThat(content.name()).isEqualTo("合同扫描件.pdf");
        assertThat(content.size()).isEqualTo(2048L);
        assertThat(content.mimeType()).isEqualTo("application/pdf");
        assertThat(content.length()).isEqualTo(2048L);

        InputStream stream = new ByteArrayInputStream("draft".getBytes(StandardCharsets.UTF_8));
        when(fileApi.getFileContentStream(FILE_ID, 512L)).thenReturn(stream);
        assertThat(api.openTemporaryContent(FILE_ID, 512L)).isSameAs(stream);
        verify(fileApi).getFileContentStream(FILE_ID, 512L);
    }

    @Test
    void rejectsTemporaryContentWhenUnprotectedOrMissing() {
        // 普通附件没有受保护标记，不能借临时上传原语读出
        FileDO plain = protectedFile();
        plain.setProtectedFlag(null);
        when(fileService.getFile(FILE_ID)).thenReturn(plain);
        assertCode(BIZ_FILE_NOT_EXISTS, () -> api.temporaryContentInfo(FILE_ID));
        assertCode(BIZ_FILE_NOT_EXISTS, () -> api.openTemporaryContent(FILE_ID, 0L));

        // 文件编号不存在与普通附件同样按不存在处理，不区分是否存在
        when(fileService.getFile(999999L)).thenReturn(null);
        assertCode(BIZ_FILE_NOT_EXISTS, () -> api.temporaryContentInfo(999999L));
        assertCode(BIZ_FILE_NOT_EXISTS, () -> api.openTemporaryContent(999999L, 0L));

        // 受保护但内容缺失：拒绝而不是返回空流；长度无法确认时按 null 返回，由调用方按 size 降级
        when(fileService.getFile(FILE_ID)).thenReturn(protectedFile());
        when(fileApi.getFileContentStream(FILE_ID, 0L)).thenReturn(null);
        when(fileApi.getFileContentLength(FILE_ID)).thenReturn(null);
        assertCode(ENTRY_FILE_CONTENT_MISSING, () -> api.openTemporaryContent(FILE_ID, 0L));
        assertThat(api.temporaryContentInfo(FILE_ID).length()).isNull();
    }

    @Test
    void deletesTemporaryContentOnlyForProtectedUnmanagedFiles() {
        long plainId = 2001L;
        long managedId = 2002L;
        long missingId = 2003L;

        // 普通附件没有受保护标记，不能借清理原语删除
        FileDO plain = protectedFile();
        plain.setId(plainId);
        plain.setProtectedFlag(null);
        when(fileService.getFiles(List.of(plainId))).thenReturn(List.of(plain));
        assertCode(BIZ_FILE_NOT_EXISTS, () -> api.deleteTemporaryContent(plainId));

        // 仍有受管文件节点引用：拒绝删除，调用方必须先解绑
        FileDO managed = protectedFile();
        managed.setId(managedId);
        when(fileService.getFiles(List.of(managedId))).thenReturn(List.of(managed));
        when(entryMapper.selectManagedListByFileId(managedId)).thenReturn(List.of(managedFile()));
        assertCode(ENTRY_MANAGED_FORBIDDEN, () -> api.deleteTemporaryContent(managedId));

        // 内容已不存在与缺省编号都按删除完成处理（幂等重试），失败路径不触发底层删除
        when(fileService.getFiles(List.of(missingId))).thenReturn(List.of());
        api.deleteTemporaryContent(missingId);
        api.deleteTemporaryContent(null);
        verify(fileApi, never()).deleteFile(anyLong());

        // 受保护且无受管节点引用：执行内容删除
        when(fileService.getFiles(List.of(FILE_ID))).thenReturn(List.of(protectedFile()));
        api.deleteTemporaryContent(FILE_ID);
        verify(fileApi).deleteFile(FILE_ID);
    }

    @Test
    void createsIndependentDirectoryAfterMultipleConcurrentNameConflicts() {
        long parentId = 920001L;
        DriveEntryDO parent = managedFile();
        parent.setId(parentId);
        parent.setType(DriveEntryTypeEnum.FOLDER.getCode());
        when(entryMapper.selectById(parentId)).thenReturn(parent);

        Set<String> concurrentlyOccupied = new HashSet<>();
        when(entryMapper.selectBySpaceAndParentAndName(eq(SPACE_ID), eq(parentId), any()))
                .thenAnswer(
                        invocation -> {
                            String name = invocation.getArgument(2);
                            if (!concurrentlyOccupied.contains(name)) {
                                return null;
                            }
                            return DriveEntryDO.builder()
                                    .id(999000L + concurrentlyOccupied.size())
                                    .spaceId(SPACE_ID)
                                    .parentId(parentId)
                                    .name(name)
                                    .type(DriveEntryTypeEnum.FOLDER.getCode())
                                    .managedBiz(Boolean.TRUE)
                                    .build();
                        });
        when(entryMapper.insertManagedFolderIfAbsent(any(), eq(10001L)))
                .thenAnswer(
                        invocation -> {
                            DriveEntryDO entry = invocation.getArgument(0);
                            if (concurrentlyOccupied.size() < 2) {
                                concurrentlyOccupied.add(entry.getName());
                                return 0;
                            }
                            entry.setId(930003L);
                            return 1;
                        });

        assertThat(api.createManagedDirectory(SPACE_ID, parentId, "合同", 10001L)).isEqualTo(930003L);
        ArgumentCaptor<DriveEntryDO> entries = ArgumentCaptor.forClass(DriveEntryDO.class);
        verify(entryMapper, times(3)).insertManagedFolderIfAbsent(entries.capture(), eq(10001L));
        assertThat(entries.getAllValues())
                .extracting(DriveEntryDO::getName)
                .containsExactly("合同", "合同 (2)", "合同 (3)");
    }

    @Test
    void legacySpaceNameOnlyResolvesExistingSpace() {
        when(spaceMapper.selectBizByName("已有业务空间")).thenReturn(bizSpace());
        assertThat(api.ensureBusinessSpace(null, "已有业务空间", 10001L)).isEqualTo(SPACE_ID);

        assertCode(SPACE_NOT_EXISTS, () -> api.ensureBusinessSpace(null, "不存在的业务空间", 10001L));
        verify(spaceMapper, never()).insert(any(DriveSpaceDO.class));
    }

    private static void assertCode(ErrorCode expected, Runnable call) {
        try {
            call.run();
            fail("预期抛出业务异常");
        } catch (ServiceException exception) {
            assertThat(exception.getCode()).isEqualTo(expected.getCode());
        }
    }

    private static DriveEntryDO managedFile() {
        return DriveEntryDO.builder()
                .id(ENTRY_ID)
                .spaceId(SPACE_ID)
                .name("合同扫描件.pdf")
                .type(DriveEntryTypeEnum.FILE.getCode())
                .fileId(FILE_ID)
                .size(2048L)
                .mimeType("application/pdf")
                .managedBiz(Boolean.TRUE)
                .build();
    }

    private static DriveSpaceDO bizSpace() {
        return DriveSpaceDO.builder()
                .id(SPACE_ID)
                .name("浏览验证空间")
                .type(DriveSpaceTypeEnum.BIZ.getCode())
                .status(0)
                .build();
    }

    private static FileDO protectedFile() {
        return FileDO.builder()
                .id(FILE_ID)
                .name("合同扫描件.pdf")
                .size(2048L)
                .type("application/pdf")
                .protectedFlag(Boolean.TRUE)
                .build();
    }
}
