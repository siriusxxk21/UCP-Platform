package com.richuang.os.nocode.tools;

import static com.richuang.os.module.drive.enums.ErrorCodeConstants.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.framework.common.exception.ErrorCode;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntryListReqVO;
import com.richuang.os.module.drive.controller.admin.entry.vo.DriveEntrySearchReqVO;
import com.richuang.os.module.drive.controller.admin.space.vo.DriveSpaceRespVO;
import com.richuang.os.module.drive.dal.dataobject.entry.DriveEntryDO;
import com.richuang.os.module.drive.enums.permission.DrivePermissionRoleEnum;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

/**
 * 业务空间的两个区：附件归档区（受管节点）对普通网盘入口一律不可见；文件夹区（普通节点）只对网盘管理员开放。真实网盘实现 + 测试库。
 *
 * <p>夹具（每条用例新建）：业务空间 B 里受管目录 {@code /合同/2026/x.pdf}、普通目录 {@code /资料/y.pdf}。管理员 G 持有 {@code
 * drive:space:update}，普通用户 P 没有。
 */
class DriveBizFolderZoneTest {
    private static final long G = 930001L;
    private static final long P = 930002L;

    private static ConfigurableApplicationContext tool;
    private static DriveFolderTestBed bed;

    private long b;
    private long managedDir;
    private long managedFile;
    private long material;
    private long y;

    @BeforeAll
    static void open() {
        tool = NocodeToolContext.open();
        bed = DriveFolderTestBed.open(tool);
        Mockito.when(bed.permissionApi.hasAnyPermissions(G, "drive:space:update")).thenReturn(true);
    }

    @AfterAll
    static void close() {
        if (bed != null) bed.close();
        if (tool != null) tool.close();
    }

    @BeforeEach
    void fixture(TestInfo info) {
        b = bed.space("B-" + info.getTestMethod().orElseThrow().getName(), "BIZ", null);
        managedDir = bed.bizFiles.ensureDirectory(b, List.of("合同", "2026"), G);
        managedFile =
                bed.jdbc.queryForObject(
                        "INSERT INTO public.drive_entry(space_id,parent_id,name,type,file_id,size,"
                                + "mime_type,managed_biz) VALUES (?,?,'x.pdf','FILE',?,5,"
                                + "'application/pdf',true) RETURNING id",
                        Long.class,
                        b,
                        managedDir,
                        bed.files
                                .store(new byte[] {1, 2, 3, 4, 5}, "x.pdf", "application/pdf")
                                .getId());
        material = bed.folder(b, 0L, "资料", G);
        y = bed.file(b, material, "y.pdf", G);
    }

    private static void rejected(ErrorCode code, ThrowingCallable call) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(code.getCode()));
    }

    private List<DriveEntryDO> list(long parent, long user) {
        DriveEntryListReqVO request = new DriveEntryListReqVO();
        request.setSpaceId(b);
        request.setParentId(parent);
        return bed.entryService.getEntryList(request, user);
    }

    private long contractRoot() {
        return bed.entry(managedDir).getParentId();
    }

    @Test
    void z1OnlyDriveAdministratorHasRoleOnBusinessSpaceRoot() {
        assertThat(bed.permissionService.getEffectiveRole(b, 0L, G))
                .isEqualTo(DrivePermissionRoleEnum.MANAGER);
        assertThat(bed.permissionService.getEffectiveRole(b, material, G))
                .isEqualTo(DrivePermissionRoleEnum.MANAGER);
        assertThat(bed.permissionService.getEffectiveRole(b, y, G))
                .isEqualTo(DrivePermissionRoleEnum.MANAGER);
        assertThat(bed.permissionService.getEffectiveRole(b, 0L, P)).isNull();
        assertThat(bed.permissionService.getEffectiveRole(b, material, P)).isNull();
        rejected(PERMISSION_DENIED, () -> list(0L, P));
        rejected(PERMISSION_DENIED, () -> list(material, P));
        rejected(PERMISSION_DENIED, () -> bed.entryService.getContentInfo(y, P));
    }

    @Test
    void z2ManagedNodesStayInvisibleEvenToAdministrator() {
        assertThat(bed.permissionService.getEffectiveRole(b, contractRoot(), G)).isNull();
        assertThat(bed.permissionService.getEffectiveRole(b, managedDir, G)).isNull();
        assertThat(bed.permissionService.getEffectiveRole(b, managedFile, G)).isNull();
        rejected(PERMISSION_DENIED, () -> bed.entryService.getContentInfo(managedFile, G));
        rejected(PERMISSION_DENIED, () -> bed.entryService.getContentStream(managedFile, G, 0));
        rejected(PERMISSION_DENIED, () -> bed.entryService.getEntry(managedFile, G));
        rejected(PERMISSION_DENIED, () -> list(managedDir, G));
        // 节点不在这个空间、节点不存在：同样没有角色。
        long other = bed.space("B-z2-other", "BIZ", null);
        assertThat(bed.permissionService.getEffectiveRole(other, material, G)).isNull();
        assertThat(bed.permissionService.getEffectiveRole(b, -1L, G)).isNull();
    }

    @Test
    void z3RootListingShowsOrdinaryFoldersOnly() {
        assertThat(list(0L, G)).extracting(DriveEntryDO::getName).containsExactly("资料");
        assertThat(list(material, G)).extracting(DriveEntryDO::getName).containsExactly("y.pdf");
    }

    @Test
    void z4AdministratorCreatesAndUploadsInFolderZone() {
        long drawings = bed.folder(b, 0L, "图纸", G);
        long uploaded = bed.file(b, material, "z.pdf", G);

        assertThat(bed.entry(drawings).getManagedBiz()).isFalse();
        assertThat(bed.entry(drawings).getParentId()).isZero();
        assertThat(bed.entry(uploaded).getParentId()).isEqualTo(material);
        assertThat(list(0L, G)).extracting(DriveEntryDO::getName).containsExactly("图纸", "资料");
        // 受管目录仍不接收普通新建与上传。
        rejected(PERMISSION_DENIED, () -> bed.folder(b, managedDir, "x", G));
        rejected(PERMISSION_DENIED, () -> bed.file(b, managedDir, "x.pdf", G));
    }

    @Test
    void z5ManagedFolderNameIsTaken() {
        rejected(ENTRY_NAME_DUPLICATE, () -> bed.folder(b, 0L, "合同", G));
    }

    @Test
    void z6LegacyGrantRowsDoNotOpenBusinessSpace() {
        bed.grant(b, 0L, P, "MANAGER");
        bed.grant(b, material, P, "MANAGER");

        assertThat(bed.permissionService.getEffectiveRole(b, 0L, P)).isNull();
        assertThat(bed.permissionService.getEffectiveRole(b, material, P)).isNull();
        assertThat(bed.permissionService.getEffectiveRole(b, y, P)).isNull();
        rejected(PERMISSION_DENIED, () -> list(0L, P));
        assertThat(bed.spaceService.getBusinessSpaceList(P))
                .extracting(DriveSpaceRespVO::getId)
                .doesNotContain(b);
    }

    @Test
    void z7SearchReturnsOrdinaryNodesOnly() {
        DriveEntrySearchReqVO request = new DriveEntrySearchReqVO();
        request.setSpaceId(b);
        request.setName("pdf");

        assertThat(bed.entryService.searchEntryList(request, G))
                .extracting(DriveEntryDO::getName)
                .containsExactly("y.pdf");
        rejected(PERMISSION_DENIED, () -> bed.entryService.searchEntryList(request, P));
    }

    @Test
    void z8BusinessSpaceListFollowsRootRole() {
        List<DriveSpaceRespVO> forAdministrator = bed.spaceService.getBusinessSpaceList(G);
        DriveSpaceRespVO found =
                forAdministrator.stream()
                        .filter(space -> space.getId().equals(b))
                        .findFirst()
                        .orElseThrow();
        assertThat(found.getRole()).isEqualTo("MANAGER");
        assertThat(found.getType()).isEqualTo("BIZ");
        assertThat(found.getOwnerId()).isNull();
        assertThat(found.getOwnerName()).isNull();
        // 全部开启的业务空间，按名称排序（数据库的排序规则）。
        assertThat(forAdministrator)
                .extracting(DriveSpaceRespVO::getId)
                .containsExactlyElementsOf(
                        bed.jdbc.queryForList(
                                "SELECT id FROM public.drive_space WHERE type='BIZ' AND status=0"
                                        + " AND deleted=0 ORDER BY name",
                                Long.class));
        assertThat(bed.spaceService.getBusinessSpaceList(P)).isEmpty();

        bed.jdbc.update("UPDATE public.drive_space SET status=1 WHERE id=?", b);
        assertThat(bed.spaceService.getBusinessSpaceList(G))
                .extracting(DriveSpaceRespVO::getId)
                .doesNotContain(b);
    }

    @Test
    void z9AttachmentArchiveRefusesOrdinaryFolderOfSameName() {
        bed.folder(b, 0L, "档案", G);

        assertThatThrownBy(() -> bed.bizFiles.ensureDirectory(b, List.of("档案", "2026"), G))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        error -> {
                            assertThat(error.getCode())
                                    .isEqualTo(ENTRY_ORDINARY_NAME_TAKEN.getCode());
                            assertThat(error.getMessage()).contains("「档案」");
                        });
    }
}
