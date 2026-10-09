package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.module.drive.api.bizfile.DriveBizFileApi;
import com.lingan.ucp.module.drive.api.bizfile.dto.DriveBizFileContent;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.enums.ApplicationActionEnum;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileBrowseService;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileMarkService;

import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;

/**
 * 在当前开发库验证业务文件浏览读模型：规则版本 → 业务分组 → 记录目录 → 附件字段/明细区 → 明细行 → 附件字段 逐层下钻，文件名搜索与整卷统计与记录授权条件在同一 SQL
 * 内完成过滤；名称来源字段不可读时只返回受限标签， 且提供原始分组值无法命中受限分组。内容读取按同一授权链定位绑定行后再取节点侧内容信息，
 * 已知节点编号不能单独读取。定位链按同一位置身份返回面包屑且不重复统计；收藏与最近访问按用户独立保存，展示前重过授权链且不作为读取依据。
 * 业务文件绑定与用户标记表不在通用清理范围，本测试按对象前缀自行清理。
 */
class BizFileBrowseIntegrationTest {
    private static final long SPACE_ID = 910001L;
    private static final long RECORD_ENTRY_ID = 920001L;
    private static final String RECORD_ID = "1";

    /** 分组绑定键串：保存时写入的来源值，第二层保留日期原文，展示名由规则格式派生 */
    private static final String GROUP_KEYS = "销售部|2026-03-05";

    private NocodeIntegrationSupport fixture;
    private BizFileBrowseService browse;
    private BizFileMarkService marks;
    private DataObjectApi objects;
    private ApplicationService apps;
    private ObjectSharingService sharing;

    private String objectId;
    private String detailId;
    private String titleId;
    private String filesId;
    private String docId;
    private int ruleVersion;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        browse = servicesContext.getBean(BizFileBrowseService.class);
        marks = servicesContext.getBean(BizFileMarkService.class);
        objects = servicesContext.getBean(DataObjectApi.class);
        apps = servicesContext.getBean(ApplicationService.class);
        sharing = servicesContext.getBean(ObjectSharingService.class);
        PermissionCommonApi permission = servicesContext.getBean(PermissionCommonApi.class);
        org.mockito.Mockito.when(permission.hasAnyPermissions(10001L, "nocode:object:query"))
                .thenReturn(true);
        org.mockito.Mockito.when(permission.hasAnyPermissions(10001L, "nocode:object:manage"))
                .thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        List<Long> objectIds =
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_object WHERE object_code LIKE ?",
                        Long.class,
                        fixture.prefix + "%");
        for (Long id : objectIds) {
            jdbc.update(
                    "DELETE FROM public.nocode_biz_attachment_binding WHERE object_id=?",
                    id.toString());
            jdbc.update(
                    "DELETE FROM public.nocode_biz_directory_binding WHERE object_id=?",
                    id.toString());
            jdbc.update(
                    "DELETE FROM public.nocode_biz_upload_session WHERE object_id=?",
                    id.toString());
            jdbc.update("DELETE FROM public.nocode_biz_file_mark WHERE object_id=?", id.toString());
        }
        List<Long> appIds =
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        fixture.prefix + "%");
        for (Long id : appIds) {
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant_log WHERE application_id=?",
                    id);
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant WHERE application_id=?",
                    id);
            jdbc.update("DELETE FROM public.nocode_application_access WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application_version WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
        }
        fixture.clean();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_object WHERE object_code LIKE"
                                        + " ?",
                                Integer.class,
                                fixture.prefix + "%"))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_application WHERE app_code LIKE"
                                        + " ?",
                                Integer.class,
                                fixture.prefix + "%"))
                .isZero();
    }

    @Test
    void defaultRecordTitleIsSharedByDirectoryFilesLocationAndFavorites() {
        publishFixture(List.of(), null);
        insertBindings();
        assertThat(
                        browse.directories(
                                        directory(
                                                null,
                                                ruleVersion,
                                                List.of("销售部", "2026-03-05"),
                                                null,
                                                null,
                                                null),
                                        10001)
                                .getList())
                .singleElement()
                .satisfies(
                        d -> {
                            assertThat(d.label()).isEqualTo("采购合同A");
                            assertThat(d.labelStatus()).isEqualTo("NORMAL");
                            assertThat(d.restricted()).isFalse();
                        });
        assertThat(
                        browse.files(
                                        files(
                                                null, null, List.of(), null, null, null, null,
                                                "采购合同A"),
                                        10001)
                                .getList())
                .hasSize(3)
                .allSatisfy(
                        f -> {
                            assertThat(f.recordId()).isEqualTo(RECORD_ID);
                            assertThat(f.recordLabel()).isEqualTo("采购合同A");
                            assertThat(f.recordLabelStatus()).isEqualTo("NORMAL");
                        });
        assertThat(
                        browse.locate(
                                contentQuery(null, RECORD_ID, null, null, filesId, 930001L), 10001))
                .filteredOn(d -> "RECORD".equals(d.kind()))
                .singleElement()
                .satisfies(d -> assertThat(d.label()).isEqualTo("采购合同A"));
        marks.favorite(favorite(null, RECORD_ID, null, null, filesId, 930001L, true), 10001);
        assertThat(
                        marks.markedFiles(
                                        new BusinessFiles.MarkQuery(null, objectId, "FAVORITE"),
                                        10001)
                                .getList())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.recordLabel()).isEqualTo("采购合同A");
                            assertThat(f.fieldLabel()).isEqualTo("合同附件");
                        });
        assertThat(
                        browse.files(
                                        files(
                                                null, null, List.of(), RECORD_ID, detailId, "a",
                                                docId, null),
                                        10001)
                                .getList())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.detailLabel()).isEqualTo("明细区");
                            assertThat(f.rowLabel()).isEqualTo("行 a");
                            assertThat(f.fieldLabel()).isEqualTo("行附件");
                        });
    }

    @Test
    void defaultTemplateUsesReadableValuesAndCannotSearchHiddenTitle() {
        publishFixture(List.of(), "{{dept}} · {{name}}");
        insertBindings();
        assertThat(
                        browse.files(
                                        files(
                                                null,
                                                null,
                                                List.of(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                "销售部 · 采购合同A"),
                                        10001)
                                .getList())
                .hasSize(3)
                .allSatisfy(
                        f -> {
                            assertThat(f.recordLabel()).isEqualTo("销售部 · 采购合同A");
                            assertThat(f.recordLabelStatus()).isEqualTo("NORMAL");
                        });
        String app = publishRestrictedApplication();
        assertThat(
                        browse.files(
                                        files(
                                                app, null, List.of(), null, null, null, null,
                                                "采购合同A"),
                                        10001)
                                .getTotal())
                .isZero();
        assertThat(
                        browse.files(
                                        files(app, null, List.of(), null, null, null, null, "销售部"),
                                        10001)
                                .getTotal())
                .isZero();
        assertThat(
                        browse.files(
                                        files(
                                                app, null, List.of(), null, null, null, null,
                                                "合同扫描件"),
                                        10001)
                                .getList())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.recordRestricted()).isTrue();
                            assertThat(f.recordLabelStatus()).isEqualTo("RESTRICTED");
                            assertThat(f.recordLabel()).doesNotContain("采购合同A", "销售部");
                        });
    }

    @Test
    void liveTitleIsEvaluatedInsteadOfReadingStaleStoredValue() {
        publishFixture(List.of(), "{{live_total}}", true);
        insertBindings();
        jdbc.update(
                "UPDATE public.\"" + mainTable() + "\" SET amount=21,live_total=999 WHERE id=1");
        assertThat(
                        browse.files(
                                        files(null, null, List.of(), null, null, null, null, null),
                                        10001)
                                .getList())
                .hasSize(3)
                .allSatisfy(
                        f -> {
                            assertThat(new java.math.BigDecimal(f.recordLabel()))
                                    .isEqualByComparingTo("42");
                            assertThat(f.recordLabelStatus()).isEqualTo("NORMAL");
                        });
        jdbc.update("UPDATE public.\"" + mainTable() + "\" SET amount=30 WHERE id=1");
        assertThat(
                        browse.directories(
                                        directory(
                                                null,
                                                ruleVersion,
                                                List.of("销售部", "2026-03-05"),
                                                null,
                                                null,
                                                null),
                                        10001)
                                .getList())
                .singleElement()
                .satisfies(
                        d ->
                                assertThat(new java.math.BigDecimal(d.label()))
                                        .isEqualByComparingTo("60"));
        assertThat(
                        browse.locate(
                                contentQuery(null, RECORD_ID, null, null, filesId, 930001L), 10001))
                .filteredOn(d -> "RECORD".equals(d.kind()))
                .singleElement()
                .satisfies(
                        d ->
                                assertThat(new java.math.BigDecimal(d.label()))
                                        .isEqualByComparingTo("60"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT live_total::text FROM public.\""
                                        + mainTable()
                                        + "\" WHERE id=1",
                                String.class))
                .startsWith("999");
    }

    @Test
    void emptyReadableTitleIsNotConfusedWithHiddenTitle() {
        publishFixture(List.of(), null);
        insertBindings();
        jdbc.update("UPDATE public.\"" + mainTable() + "\" SET name=NULL WHERE id=1");
        assertThat(
                        browse.files(
                                        files(null, null, List.of(), null, null, null, null, null),
                                        10001)
                                .getList())
                .hasSize(3)
                .allSatisfy(
                        f -> {
                            assertThat(f.recordLabel()).isEqualTo("未填写标题 · ID 1");
                            assertThat(f.recordRestricted()).isFalse();
                            assertThat(f.recordLabelStatus()).isEqualTo("EMPTY");
                        });
        assertThat(
                        browse.directories(
                                        directory(
                                                null,
                                                ruleVersion,
                                                List.of("销售部", "2026-03-05"),
                                                null,
                                                null,
                                                null),
                                        10001)
                                .getList())
                .singleElement()
                .satisfies(
                        d -> {
                            assertThat(d.labelStatus()).isEqualTo("EMPTY");
                            assertThat(d.restricted()).isFalse();
                        });
        String app = publishRestrictedApplication();
        assertThat(
                        browse.files(
                                        files(app, null, List.of(), null, null, null, null, null),
                                        10001)
                                .getList())
                .hasSize(3)
                .allSatisfy(
                        f -> {
                            assertThat(f.recordRestricted()).isTrue();
                            assertThat(f.recordLabelStatus()).isEqualTo("RESTRICTED");
                        });
    }

    @Test
    void sameTitlesKeepSeparateRecordIdentitiesAndSearchDoesNotAlterBindings() {
        publishFixture();
        insertBindings();
        jdbc.update(
                "INSERT INTO public.\""
                        + mainTable()
                        + "\" (id,creator,create_time,updater,update_time,deleted,name,dept,signed)"
                        + " SELECT"
                        + " 2,creator,create_time,updater,update_time,deleted,name,dept,signed FROM"
                        + " public.\""
                        + mainTable()
                        + "\" WHERE id=1");
        jdbc.update(
                "INSERT INTO public.nocode_biz_directory_binding"
                    + " (object_id,record_id,space_id,entry_id,rule_version,group_keys,creator,updater)"
                    + " VALUES (?, '2', ?, 920002, ?, ?, '10001', '10001')",
                objectId,
                SPACE_ID,
                ruleVersion,
                GROUP_KEYS);
        jdbc.update(
                "INSERT INTO public.nocode_biz_attachment_binding"
                    + " (object_id,record_id,detail_id,row_id,field_id,file_id,entry_id,space_id,state,file_name,file_size,mime_type,source_entry,creator,updater)"
                    + " VALUES (?, '2', '', '', ?, 1004, 930004, ?, 'ACTIVE', '另一条记录.pdf', 10,"
                    + " 'application/pdf','OBJECT_MAINTENANCE','10001','10001')",
                objectId,
                filesId,
                SPACE_ID);
        assertThat(
                        browse.directories(
                                        directory(
                                                null,
                                                ruleVersion,
                                                List.of("销售部", "2026-03-05"),
                                                null,
                                                null,
                                                null),
                                        10001)
                                .getList())
                .extracting(BusinessFiles.Directory::recordId, BusinessFiles.Directory::label)
                .containsExactlyInAnyOrder(tuple("1", "采购合同A"), tuple("2", "采购合同A"));
        assertThat(
                        browse.files(
                                        files(
                                                null, null, List.of(), null, null, null, null,
                                                "采购合同A"),
                                        10001)
                                .getList())
                .hasSize(4)
                .extracting(BusinessFiles.File::recordId)
                .contains("1", "2");
        assertThat(browse.locate(contentQuery(null, "2", null, null, filesId, 930004L), 10001))
                .filteredOn(d -> "RECORD".equals(d.kind()))
                .singleElement()
                .satisfies(d -> assertThat(d.recordId()).isEqualTo("2"));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_biz_directory_binding WHERE"
                                        + " object_id=?",
                                Integer.class,
                                objectId))
                .isEqualTo(2);
    }

    @Test
    void maintenanceEntryBrowsesEveryLevelAndSearchesFilesInsideAuthorization() {
        publishFixture();
        insertBindings();

        List<BusinessFiles.Space> spaces = browse.spaces(new BusinessFiles.EntryQuery(null), 10001);
        assertThat(spaces)
                .filteredOn(s -> s.objectId().equals(objectId))
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.objectName()).isEqualTo("业务文件浏览验证");
                            assertThat(s.spaceName()).isEqualTo("浏览验证空间");
                            assertThat(s.fixedPath()).containsExactly("合同");
                            assertThat(s.currentRuleVersion()).isEqualTo(ruleVersion);
                            assertThat(s.fileCount()).isEqualTo(3);
                            assertThat(s.totalSize()).isEqualTo(3584L);
                            assertThat(s.recordCount()).isEqualTo(1);
                        });

        // 规则版本根节点：未指定版本时按绑定版本列出
        var versions =
                browse.directories(directory(null, null, List.of(), null, null, null), 10001);
        assertThat(versions.getTotal()).isEqualTo(1L);
        assertThat(versions.getList())
                .singleElement()
                .satisfies(
                        d -> {
                            assertThat(d.kind()).isEqualTo("VERSION");
                            assertThat(d.ruleVersion()).isEqualTo(ruleVersion);
                            assertThat(d.label()).isEqualTo("当前规则（v" + ruleVersion + "）");
                            assertThat(d.fileCount()).isEqualTo(3);
                            assertThat(d.totalSize()).isEqualTo(3584L);
                            assertThat(d.recordCount()).isEqualTo(1);
                        });

        // 业务分组第一层：来源值可读时返回原始值与展示名
        var groups =
                browse.directories(
                        directory(null, ruleVersion, List.of(), null, null, null), 10001);
        assertThat(groups.getTotal()).isEqualTo(1L);
        assertThat(groups.getList())
                .singleElement()
                .satisfies(
                        d -> {
                            assertThat(d.kind()).isEqualTo("GROUP");
                            assertThat(d.groupKey()).isEqualTo("销售部");
                            assertThat(d.label()).isEqualTo("销售部");
                            assertThat(d.restricted()).isFalse();
                            assertThat(d.fileCount()).isEqualTo(3);
                        });

        // 业务分组第二层：日期按 YEAR 格式派生目录名，导航键仍是绑定键原文
        var years =
                browse.directories(
                        directory(null, ruleVersion, List.of("销售部"), null, null, null), 10001);
        assertThat(years.getList())
                .singleElement()
                .satisfies(
                        d -> {
                            assertThat(d.groupKey()).isEqualTo("2026-03-05");
                            assertThat(d.label()).isEqualTo("2026");
                            assertThat(d.restricted()).isFalse();
                        });

        // 记录目录：名称由标签来源字段投影解析
        var records =
                browse.directories(
                        directory(
                                null, ruleVersion, List.of("销售部", "2026-03-05"), null, null, null),
                        10001);
        assertThat(records.getList())
                .singleElement()
                .satisfies(
                        d -> {
                            assertThat(d.kind()).isEqualTo("RECORD");
                            assertThat(d.recordId()).isEqualTo(RECORD_ID);
                            assertThat(d.label()).isEqualTo("采购合同A");
                            assertThat(d.restricted()).isFalse();
                            assertThat(d.fileCount()).isEqualTo(3);
                            assertThat(d.recordCount()).isEqualTo(1);
                        });

        // 记录内目录：附件字段目录与明细区目录，分别取对象字段名与明细名
        var children =
                browse.directories(
                        directory(
                                null,
                                ruleVersion,
                                List.of("销售部", "2026-03-05"),
                                RECORD_ID,
                                null,
                                null),
                        10001);
        assertThat(children.getTotal()).isEqualTo(2L);
        assertThat(children.getList())
                .extracting(
                        BusinessFiles.Directory::kind,
                        BusinessFiles.Directory::label,
                        BusinessFiles.Directory::fileCount,
                        BusinessFiles.Directory::totalSize)
                .containsExactlyInAnyOrder(
                        tuple("FIELD", "合同附件", 1, 2048L), tuple("REGION", "明细区", 2, 1536L));

        // 明细行层：行目录按持久行 ID 命名
        var rows =
                browse.directories(
                        directory(
                                null,
                                ruleVersion,
                                List.of("销售部", "2026-03-05"),
                                RECORD_ID,
                                detailId,
                                null),
                        10001);
        assertThat(rows.getTotal()).isEqualTo(2L);
        assertThat(rows.getList())
                .extracting(
                        BusinessFiles.Directory::kind,
                        BusinessFiles.Directory::rowId,
                        BusinessFiles.Directory::label,
                        BusinessFiles.Directory::fileCount)
                .containsExactlyInAnyOrder(
                        tuple("ROW", "a", "行 a", 1), tuple("ROW", "b", "行 b", 1));

        // 明细行内附件字段目录
        var rowFields =
                browse.directories(
                        directory(
                                null,
                                ruleVersion,
                                List.of("销售部", "2026-03-05"),
                                RECORD_ID,
                                detailId,
                                "a"),
                        10001);
        assertThat(rowFields.getList())
                .singleElement()
                .satisfies(
                        d -> {
                            assertThat(d.kind()).isEqualTo("FIELD");
                            assertThat(d.fieldId()).isEqualTo(docId);
                            assertThat(d.label()).isEqualTo("行附件");
                            assertThat(d.fileCount()).isEqualTo(1);
                        });

        // 文件列表：整卷视角按创建时间倒序，含记录标签与提交人
        var all =
                browse.files(
                        files(null, ruleVersion, List.of(), null, null, null, null, null), 10001);
        assertThat(all.getTotal()).isEqualTo(3L);
        assertThat(all.getList())
                .extracting(BusinessFiles.File::fileId)
                .containsExactly(1003L, 1002L, 1001L);
        assertThat(all.getList().getFirst())
                .satisfies(
                        f -> {
                            assertThat(f.entryId()).isEqualTo(930003L);
                            assertThat(f.spaceId()).isEqualTo(SPACE_ID);
                            assertThat(f.name()).isEqualTo("附件说明.pdf");
                            assertThat(f.size()).isEqualTo(512L);
                            assertThat(f.mimeType()).isEqualTo("application/pdf");
                            assertThat(f.recordId()).isEqualTo(RECORD_ID);
                            assertThat(f.recordLabel()).isEqualTo("采购合同A");
                            assertThat(f.recordRestricted()).isFalse();
                            assertThat(f.detailId()).isEqualTo(detailId);
                            assertThat(f.rowId()).isEqualTo("b");
                            assertThat(f.fieldId()).isEqualTo(docId);
                            assertThat(f.submitter()).isEqualTo("10001");
                            assertThat(f.uploadedAt()).isNotBlank();
                        });

        // 字段与明细身份限定
        assertThat(
                        browse.files(
                                        files(
                                                null,
                                                ruleVersion,
                                                List.of("销售部", "2026-03-05"),
                                                RECORD_ID,
                                                null,
                                                null,
                                                filesId,
                                                null),
                                        10001)
                                .getList())
                .singleElement()
                .satisfies(f -> assertThat(f.fileId()).isEqualTo(1001L));
        assertThat(
                        browse.files(
                                        files(
                                                null,
                                                ruleVersion,
                                                List.of("销售部", "2026-03-05"),
                                                RECORD_ID,
                                                detailId,
                                                "a",
                                                null,
                                                null),
                                        10001)
                                .getList())
                .singleElement()
                .satisfies(f -> assertThat(f.fileId()).isEqualTo(1002L));

        // 文件名搜索与通配符转义：% 与 _ 按字面量匹配
        var searched =
                browse.files(
                        files(null, ruleVersion, List.of(), null, null, null, null, "盖章"), 10001);
        assertThat(searched.getTotal()).isEqualTo(1L);
        assertThat(searched.getList().getFirst().fileId()).isEqualTo(1002L);
        assertThat(
                        browse.files(
                                        files(
                                                null,
                                                ruleVersion,
                                                List.of(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                "%"),
                                        10001)
                                .getList())
                .isEmpty();
        assertThat(
                        browse.files(
                                        files(
                                                null,
                                                ruleVersion,
                                                List.of(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                "_"),
                                        10001)
                                .getList())
                .isEmpty();
    }

    @Test
    void applicationEntryReturnsRestrictedTokensForUnreadableGroupAndRecordLabels() {
        publishFixture();
        insertBindings();
        String app = publishRestrictedApplication();

        // 入口准入：非应用成员不能读取；三个端点每次都重验完整授权链
        assertThatThrownBy(() -> browse.spaces(new BusinessFiles.EntryQuery(app), 20002))
                .hasMessageContaining("没有此应用的运行权限");
        assertThatThrownBy(
                        () ->
                                browse.directories(
                                        directory(app, ruleVersion, List.of(), null, null, null),
                                        20002))
                .hasMessageContaining("没有此应用的运行权限");
        assertThatThrownBy(
                        () ->
                                browse.files(
                                        files(
                                                app,
                                                ruleVersion,
                                                List.of(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null),
                                        20002))
                .hasMessageContaining("没有此应用的运行权限");

        var groups =
                browse.directories(directory(app, ruleVersion, List.of(), null, null, null), 10001);
        assertThat(groups.getList())
                .singleElement()
                .satisfies(
                        d -> {
                            assertThat(d.restricted()).isTrue();
                            assertThat(d.label()).startsWith("受限分组 · ");
                            assertThat(d.label()).doesNotContain("销售部");
                            assertThat(d.groupKey()).isEqualTo(md5("销售部"));
                        });
        String deptToken = groups.getList().getFirst().groupKey();

        // 提供原始分组值不能命中受限分组
        assertThat(
                        browse.directories(
                                        directory(
                                                app, ruleVersion, List.of("销售部"), null, null, null),
                                        10001)
                                .getList())
                .isEmpty();

        var years =
                browse.directories(
                        directory(app, ruleVersion, List.of(deptToken), null, null, null), 10001);
        assertThat(years.getList())
                .singleElement()
                .satisfies(
                        d -> {
                            assertThat(d.restricted()).isTrue();
                            assertThat(d.groupKey()).isEqualTo(md5("2026-03-05"));
                        });
        String yearToken = years.getList().getFirst().groupKey();

        // 记录标签来源字段不可读：退化为安全短标识
        var records =
                browse.directories(
                        directory(
                                app, ruleVersion, List.of(deptToken, yearToken), null, null, null),
                        10001);
        assertThat(records.getList())
                .singleElement()
                .satisfies(
                        d -> {
                            assertThat(d.kind()).isEqualTo("RECORD");
                            assertThat(d.recordId()).isEqualTo(RECORD_ID);
                            assertThat(d.restricted()).isTrue();
                            assertThat(d.label()).isEqualTo("记录 1");
                        });

        // 授权内的字段目录与明细区目录仍按契约展示
        var children =
                browse.directories(
                        directory(
                                app,
                                ruleVersion,
                                List.of(deptToken, yearToken),
                                RECORD_ID,
                                null,
                                null),
                        10001);
        assertThat(children.getList())
                .extracting(BusinessFiles.Directory::kind, BusinessFiles.Directory::label)
                .containsExactlyInAnyOrder(tuple("FIELD", "合同附件"), tuple("REGION", "明细区"));

        var files =
                browse.files(
                        files(
                                app,
                                ruleVersion,
                                List.of(deptToken, yearToken),
                                RECORD_ID,
                                null,
                                null,
                                null,
                                null),
                        10001);
        assertThat(files.getTotal()).isEqualTo(3L);
        assertThat(files.getList()).allSatisfy(f -> assertThat(f.recordRestricted()).isTrue());

        // 空间统计按当前授权集过滤
        assertThat(browse.spaces(new BusinessFiles.EntryQuery(app), 10001))
                .singleElement()
                .satisfies(
                        s -> {
                            assertThat(s.fileCount()).isEqualTo(3);
                            assertThat(s.totalSize()).isEqualTo(3584L);
                            assertThat(s.recordCount()).isEqualTo(1);
                        });
    }

    @Test
    void contentReadsOnlyInsideTheFullAuthorizationChain() {
        publishFixture();
        insertBindings();
        DriveBizFileApi drive = servicesContext.getBean(DriveBizFileApi.class);
        org.mockito.Mockito.when(drive.contentInfo(930001L))
                .thenReturn(
                        new DriveBizFileContent(
                                930001L,
                                SPACE_ID,
                                1001L,
                                "合同扫描件.pdf",
                                2048L,
                                "application/pdf",
                                2048L));

        // 维护入口：位置身份与安全集匹配时返回节点侧内容元信息
        BusinessFiles.Content content =
                browse.content(contentQuery(null, RECORD_ID, null, null, filesId, 930001L), 10001);
        assertThat(content.entryId()).isEqualTo(930001L);
        assertThat(content.fileId()).isEqualTo(1001L);
        assertThat(content.name()).isEqualTo("合同扫描件.pdf");
        assertThat(content.size()).isEqualTo(2048L);
        assertThat(content.mimeType()).isEqualTo("application/pdf");
        assertThat(content.length()).isEqualTo(2048L);

        // 内容流按给定偏移打开，控制器只在授权链通过后调用
        org.mockito.Mockito.when(drive.openContent(930001L, 1024L))
                .thenReturn(new ByteArrayInputStream("contract".getBytes(StandardCharsets.UTF_8)));
        try (InputStream stream = browse.contentStream(content, 1024L)) {
            assertThat(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("contract");
        } catch (IOException broken) {
            throw new IllegalStateException(broken);
        }

        // A07：已知节点编号不能单独读取，必须同时落在可见记录、字段与明细位置上
        assertThatThrownBy(
                        () ->
                                browse.content(
                                        contentQuery(null, RECORD_ID, null, null, filesId, 930999L),
                                        10001))
                .hasMessageContaining("记录不存在或不可访问");
        assertThatThrownBy(
                        () ->
                                browse.content(
                                        contentQuery(null, "999", null, null, filesId, 930001L),
                                        10001))
                .hasMessageContaining("记录不存在或不可访问");
        // 位置身份必须完全匹配：主表字段位置取不到明细行附件
        assertThatThrownBy(
                        () ->
                                browse.content(
                                        contentQuery(null, RECORD_ID, null, null, filesId, 930002L),
                                        10001))
                .hasMessageContaining("记录不存在或不可访问");

        // 明细行附件按其位置可读
        org.mockito.Mockito.when(drive.contentInfo(930002L))
                .thenReturn(
                        new DriveBizFileContent(
                                930002L,
                                SPACE_ID,
                                1002L,
                                "盖章页.pdf",
                                1024L,
                                "application/pdf",
                                1024L));
        assertThat(
                        browse.content(
                                        contentQuery(
                                                null, RECORD_ID, detailId, "a", docId, 930002L),
                                        10001)
                                .name())
                .isEqualTo("盖章页.pdf");
        // 绑定行与网盘节点内容身份不一致时拒绝
        org.mockito.Mockito.when(drive.contentInfo(930003L))
                .thenReturn(
                        new DriveBizFileContent(
                                930003L,
                                SPACE_ID,
                                9999L,
                                "附件说明.pdf",
                                512L,
                                "application/pdf",
                                512L));
        assertThatThrownBy(
                        () ->
                                browse.content(
                                        contentQuery(
                                                null, RECORD_ID, detailId, "b", docId, 930003L),
                                        10001))
                .hasMessageContaining("记录不存在或不可访问");

        // 入口准入：非应用成员不能凭已知节点读取
        String app = publishRestrictedApplication();
        assertThatThrownBy(
                        () ->
                                browse.content(
                                        contentQuery(app, RECORD_ID, null, null, filesId, 930001L),
                                        20002))
                .hasMessageContaining("没有此应用的运行权限");
        // 应用入口内命中已授权字段时读取正常
        assertThat(
                        browse.content(
                                        contentQuery(app, RECORD_ID, null, null, filesId, 930001L),
                                        10001)
                                .fileId())
                .isEqualTo(1001L);
        // 附件字段未纳入授权时按不可访问处理，不因知道节点编号而放行
        String narrow = publishApplication("narrow", "业务文件字段收窄入口", Set.of(titleId));
        assertThatThrownBy(
                        () ->
                                browse.content(
                                        contentQuery(
                                                narrow, RECORD_ID, null, null, filesId, 930001L),
                                        10001))
                .hasMessageContaining("记录不存在或不可访问");
    }

    @Test
    void recordSideFilesWithoutRuleVersionSpanAllBindingVersions() {
        publishFixture();
        insertBindings();
        // 发布真实第二版并改用部门命名；旧目录必须继续采用原版的合同名称。
        DataCenter.Design published = designs.get(objectId);
        DataCenter.Design original =
                designs.editPublished(
                        new DataCenter.Revision(objectId, published.draft().lockVersion(), null),
                        10001);
        String departmentId =
                original.draft().fields().stream()
                        .filter(f -> "dept".equals(f.code()))
                        .findFirst()
                        .orElseThrow()
                        .id();
        DataCenter.BusinessFilePolicy priorPolicy = original.settings().businessFilePolicy();
        DataCenter.Design changed =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.edit(original.draft(), List.of(), List.of(), titleId),
                                new DataCenter.Settings(
                                        null,
                                        null,
                                        null,
                                        original.settings().titleTemplate(),
                                        null,
                                        new DataCenter.BusinessFilePolicy(
                                                priorPolicy.spaceId(),
                                                priorPolicy.spaceName(),
                                                priorPolicy.fixedPath(),
                                                priorPolicy.groups(),
                                                List.of(departmentId),
                                                priorPolicy.fieldIds())),
                                null,
                                null,
                                null,
                                null),
                        10001);
        DataCenter.PublishPlan nextPlan =
                publisher.plan(
                        new DataCenter.Revision(objectId, changed.draft().lockVersion(), null),
                        10001);
        assertThat(nextPlan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(
                                        new DataCenter.ExecutePlan(nextPlan.id(), "验证历史目录命名"),
                                        10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        assertThat(objects.getVersion(objectId, null).versionNo()).isEqualTo(ruleVersion + 1);
        // 第二条记录绑定在另一规则版本：指定版本时按版本过滤，记录侧不带版本时跨版本列出
        jdbc.update(
                "INSERT INTO public.\""
                        + mainTable()
                        + "\" (id, creator, create_time, updater, update_time, deleted, name, dept,"
                        + " signed) VALUES (2,?,now(),?,now(),0,?,?,?)",
                "10001",
                "10001",
                "采购合同B",
                "销售部",
                java.sql.Date.valueOf(LocalDate.of(2026, 4, 1)));
        jdbc.update(
                "INSERT INTO public.nocode_biz_directory_binding (object_id, record_id, space_id,"
                        + " entry_id, rule_version, group_keys, creator, updater) VALUES"
                        + " (?,?,?,?,?,?,?,?)",
                objectId,
                "2",
                SPACE_ID,
                920002L,
                ruleVersion + 1,
                "销售部|2026-04-01",
                "10001",
                "10001");
        jdbc.update(
                "INSERT INTO public.nocode_biz_attachment_binding (object_id, record_id, detail_id,"
                        + " row_id, field_id, file_id, entry_id, space_id, state, file_name,"
                        + " file_size, mime_type, source_entry, creator, create_time, updater,"
                        + " update_time) VALUES (?,?,'','',?,?,?,?,'ACTIVE',?,2048,"
                        + " 'application/pdf','OBJECT_MAINTENANCE','10001', now(), '10001', now())",
                objectId,
                "2",
                filesId,
                1004L,
                930004L,
                SPACE_ID,
                "历史版本.pdf");

        // 记录侧不带规则版本：该记录位置的绑定跨版本列出，每条标签按绑定的真实规则版本解析
        var record2 =
                browse.files(files(null, null, List.of(), "2", null, null, filesId, null), 10001);
        assertThat(record2.getTotal()).isEqualTo(1L);
        assertThat(record2.getList().getFirst().fileId()).isEqualTo(1004L);
        assertThat(record2.getList().getFirst().recordLabel()).isEqualTo("销售部");
        var record1 =
                browse.files(
                        files(null, null, List.of(), RECORD_ID, null, null, filesId, null), 10001);
        assertThat(record1.getList())
                .singleElement()
                .satisfies(
                        f -> {
                            assertThat(f.fileId()).isEqualTo(1001L);
                            assertThat(f.recordLabel()).isEqualTo("采购合同A");
                            assertThat(f.recordRestricted()).isFalse();
                        });
        assertThat(
                        browse.files(
                                        files(
                                                null, null, List.of(), null, null, null, null,
                                                "采购合同A"),
                                        10001)
                                .getList())
                .hasSize(3)
                .allSatisfy(f -> assertThat(f.recordId()).isEqualTo(RECORD_ID));
        assertThat(
                        browse.locate(
                                contentQuery(null, RECORD_ID, null, null, filesId, 930001L), 10001))
                .filteredOn(d -> "RECORD".equals(d.kind()))
                .singleElement()
                .satisfies(d -> assertThat(d.label()).isEqualTo("采购合同A"));
        // 指定当前规则版本时，另一版本的记录目录不在版本范围内
        assertThat(
                        browse.files(
                                        files(
                                                null,
                                                ruleVersion,
                                                List.of(),
                                                "2",
                                                null,
                                                null,
                                                filesId,
                                                null),
                                        10001)
                                .getList())
                .isEmpty();
        // 分组导航键依赖规则版本，不带版本时拒绝
        assertThatThrownBy(
                        () ->
                                browse.files(
                                        files(
                                                null,
                                                null,
                                                List.of("销售部"),
                                                RECORD_ID,
                                                null,
                                                null,
                                                filesId,
                                                null),
                                        10001))
                .hasMessageContaining("业务分组导航必须指定规则版本");
    }

    @Test
    void invalidLocationAndPagingAreRejected() {
        publishFixture();
        insertBindings();

        assertThatThrownBy(() -> browse.directories(null, 10001))
                .hasMessageContaining("业务文件目录请求不能为空");
        assertThatThrownBy(() -> browse.files(null, 10001)).hasMessageContaining("业务文件查询不能为空");
        assertThatThrownBy(
                        () ->
                                browse.directories(
                                        new BusinessFiles.DirectoryQuery(
                                                null, "abc", null, List.of(), null, null, null, 1,
                                                20),
                                        10001))
                .hasMessageContaining("数据对象标识无效");
        assertThatThrownBy(
                        () ->
                                browse.directories(
                                        directory(null, 0, List.of(), null, null, null), 10001))
                .hasMessageContaining("目录规则版本无效");
        assertThatThrownBy(
                        () ->
                                browse.directories(
                                        new BusinessFiles.DirectoryQuery(
                                                null,
                                                objectId,
                                                ruleVersion,
                                                List.of(),
                                                null,
                                                null,
                                                null,
                                                0,
                                                20),
                                        10001))
                .hasMessageContaining("业务文件分页参数无效");
        assertThatThrownBy(
                        () ->
                                browse.directories(
                                        new BusinessFiles.DirectoryQuery(
                                                null,
                                                objectId,
                                                ruleVersion,
                                                List.of(),
                                                null,
                                                null,
                                                null,
                                                1,
                                                101),
                                        10001))
                .hasMessageContaining("业务文件分页参数无效");
        assertThatThrownBy(
                        () ->
                                browse.directories(
                                        directory(
                                                null,
                                                ruleVersion,
                                                List.of("a", "b", "c"),
                                                null,
                                                null,
                                                null),
                                        10001))
                .hasMessageContaining("业务分组导航位置无效");
        assertThatThrownBy(
                        () ->
                                browse.directories(
                                        directory(
                                                null, ruleVersion, List.of(), null, detailId, null),
                                        10001))
                .hasMessageContaining("明细区目录必须指定所属记录");
        assertThatThrownBy(
                        () ->
                                browse.directories(
                                        directory(
                                                null, ruleVersion, List.of(), RECORD_ID, null, "a"),
                                        10001))
                .hasMessageContaining("明细行目录必须指定所属明细区");
        assertThatThrownBy(
                        () ->
                                browse.directories(
                                        directory(null, 999, List.of(), null, null, null), 10001))
                .hasMessageContaining("目录规则版本不存在或未接入业务文件");
        assertThatThrownBy(
                        () ->
                                browse.files(
                                        files(
                                                null,
                                                ruleVersion,
                                                List.of(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                "x".repeat(201)),
                                        10001))
                .hasMessageContaining("搜索内容不能超过 200 字");
        assertThatThrownBy(() -> browse.content(null, 10001)).hasMessageContaining("业务文件内容请求不能为空");
        assertThatThrownBy(
                        () ->
                                browse.content(
                                        contentQuery(null, null, null, null, filesId, 930001L),
                                        10001))
                .hasMessageContaining("业务文件内容必须指定所属记录");
        assertThatThrownBy(
                        () ->
                                browse.content(
                                        contentQuery(null, RECORD_ID, null, null, null, 930001L),
                                        10001))
                .hasMessageContaining("业务文件内容必须指定所属字段");
        assertThatThrownBy(
                        () ->
                                browse.content(
                                        contentQuery(null, RECORD_ID, null, null, filesId, null),
                                        10001))
                .hasMessageContaining("业务文件节点身份无效");
        assertThatThrownBy(
                        () ->
                                browse.content(
                                        contentQuery(null, RECORD_ID, null, "a", docId, 930002L),
                                        10001))
                .hasMessageContaining("明细行附件必须指定所属明细区");
        assertThatThrownBy(
                        () ->
                                browse.content(
                                        new BusinessFiles.ContentQuery(
                                                null, "abc", RECORD_ID, null, null, filesId, 1L),
                                        10001))
                .hasMessageContaining("数据对象标识无效");
    }

    @Test
    void locateReturnsNavigationChainInsideTheSameAuthorization() {
        publishFixture();
        insertBindings();

        // 数据维护入口：完整位置链按版本 → 分组 → 记录 → 明细区 → 明细行 → 附件字段返回
        List<BusinessFiles.Directory> chain =
                browse.locate(contentQuery(null, RECORD_ID, detailId, "b", docId, 930003L), 10001);
        assertThat(chain)
                .extracting(
                        BusinessFiles.Directory::kind,
                        BusinessFiles.Directory::label,
                        BusinessFiles.Directory::restricted)
                .containsExactly(
                        tuple("VERSION", "当前规则（v" + ruleVersion + "）", false),
                        tuple("GROUP", "销售部", false),
                        tuple("GROUP", "2026", false),
                        tuple("RECORD", "采购合同A", false),
                        tuple("REGION", "明细区", false),
                        tuple("ROW", "行 b", false),
                        tuple("FIELD", "行附件", false));
        // 导航身份与目录浏览一致：分组键保留绑定原文，记录/明细/行/字段逐级限定；链上不重复统计数量
        assertThat(chain)
                .allSatisfy(
                        d -> {
                            assertThat(d.fileCount()).isZero();
                            assertThat(d.totalSize()).isZero();
                        });
        assertThat(chain)
                .extracting(BusinessFiles.Directory::groupKey)
                .containsExactly(null, "销售部", "2026-03-05", null, null, null, null);
        assertThat(chain)
                .extracting(BusinessFiles.Directory::ruleVersion)
                .containsOnly(ruleVersion);
        assertThat(chain)
                .extracting(BusinessFiles.Directory::recordCount)
                .containsExactly(0, 0, 0, 1, 1, 1, 1);
        assertThat(chain.get(3).recordId()).isEqualTo(RECORD_ID);
        assertThat(chain.get(4).detailId()).isEqualTo(detailId);
        assertThat(chain.get(5).rowId()).isEqualTo("b");
        assertThat(chain.get(6).fieldId()).isEqualTo(docId);

        // 主表字段定位：链上不出现明细区与明细行层级
        List<BusinessFiles.Directory> mainChain =
                browse.locate(contentQuery(null, RECORD_ID, null, null, filesId, 930001L), 10001);
        assertThat(mainChain)
                .extracting(BusinessFiles.Directory::kind, BusinessFiles.Directory::label)
                .containsExactly(
                        tuple("VERSION", "当前规则（v" + ruleVersion + "）"),
                        tuple("GROUP", "销售部"),
                        tuple("GROUP", "2026"),
                        tuple("RECORD", "采购合同A"),
                        tuple("FIELD", "合同附件"));

        // 应用入口：分组与记录名不可读时按令牌与受限标签返回；附件字段仍在授权内
        String app = publishRestrictedApplication();
        assertThatThrownBy(
                        () ->
                                browse.locate(
                                        contentQuery(app, RECORD_ID, detailId, "b", docId, 930003L),
                                        20002))
                .hasMessageContaining("没有此应用的运行权限");
        assertThat(browse.locate(contentQuery(app, RECORD_ID, null, null, filesId, 930001L), 10001))
                .satisfies(
                        path -> {
                            assertThat(path)
                                    .extracting(BusinessFiles.Directory::kind)
                                    .containsExactly(
                                            "VERSION", "GROUP", "GROUP", "RECORD", "FIELD");
                            assertThat(path.get(1).groupKey()).isEqualTo(md5("销售部"));
                            assertThat(path.get(2).groupKey()).isEqualTo(md5("2026-03-05"));
                            assertThat(path.get(1).restricted()).isTrue();
                            assertThat(path.get(1).label())
                                    .startsWith("受限分组 · ")
                                    .doesNotContain("销售部");
                            assertThat(path.get(3).restricted()).isTrue();
                            assertThat(path.get(3).label()).isEqualTo("记录 1");
                            assertThat(path.get(4).restricted()).isFalse();
                            assertThat(path.get(4).label()).isEqualTo("合同附件");
                            assertThat(path.get(4).fieldId()).isEqualTo(filesId);
                        });

        // 位置与节点身份不匹配或字段不存在：统一按不可访问处理，不借定位探测绑定身份
        assertThatThrownBy(
                        () ->
                                browse.locate(
                                        contentQuery(null, RECORD_ID, null, null, filesId, 930002L),
                                        10001))
                .hasMessageContaining("记录不存在或不可访问");
        assertThatThrownBy(
                        () ->
                                browse.locate(
                                        contentQuery(
                                                null, RECORD_ID, null, null, "90000001", 930001L),
                                        10001))
                .hasMessageContaining("记录不存在或不可访问");
        assertThatThrownBy(() -> browse.locate(null, 10001)).hasMessageContaining("业务文件定位请求不能为空");
        assertThatThrownBy(
                        () ->
                                browse.locate(
                                        contentQuery(null, null, null, null, filesId, 930001L),
                                        10001))
                .hasMessageContaining("业务文件内容必须指定所属记录");
    }

    @Test
    void favoritesAndRecentAccessStayPerUserAndInsideAuthorizationAndMarkLimit() {
        publishFixture();
        insertBindings();
        String app = publishRestrictedApplication();
        String narrow = publishApplication("narrow", "业务文件标记收窄入口", Set.of(titleId));

        // 收藏开关：先重验可见绑定；重复收藏幂等；列表按标记时间倒序返回当前可见文件
        assertThat(
                        marks.favorite(
                                favorite(null, RECORD_ID, detailId, "b", docId, 930003L, true),
                                10001))
                .isTrue();
        assertThat(
                        marks.favorite(
                                favorite(null, RECORD_ID, detailId, "b", docId, 930003L, true),
                                10001))
                .isTrue();
        assertThat(
                        marks.markedFiles(
                                new BusinessFiles.MarkQuery(null, objectId, "FAVORITE"), 10001))
                .satisfies(
                        page -> {
                            assertThat(page.getTotal()).isEqualTo(1L);
                            BusinessFiles.File file = page.getList().getFirst();
                            assertThat(file.fileId()).isEqualTo(1003L);
                            assertThat(file.recordLabel()).isEqualTo("采购合同A");
                            assertThat(file.detailId()).isEqualTo(detailId);
                            assertThat(file.rowId()).isEqualTo("b");
                            assertThat(file.fieldId()).isEqualTo(docId);
                        });
        // 标记按用户独立：他人的标记不进入本人列表
        jdbc.update(
                "INSERT INTO public.nocode_biz_file_mark (user_id, object_id, entry_id, mark_type,"
                        + " creator, updater) VALUES (10002,?,?,'FAVORITE','10002','10002')",
                objectId,
                930003L);
        assertThat(
                        marks.markedFiles(
                                new BusinessFiles.MarkQuery(null, objectId, "FAVORITE"), 10001))
                .satisfies(page -> assertThat(page.getTotal()).isEqualTo(1L));

        // 无维护权限的账号有标记时读取被拒（无标记时直接返回空列表）
        jdbc.update(
                "INSERT INTO public.nocode_biz_file_mark (user_id, object_id, entry_id, mark_type,"
                        + " creator, updater) VALUES (20002,?,?,'FAVORITE','20002','20002')",
                objectId,
                930001L);
        assertThatThrownBy(
                        () ->
                                marks.markedFiles(
                                        new BusinessFiles.MarkQuery(null, objectId, "FAVORITE"),
                                        20002))
                .hasMessageContaining("需要数据对象管理权限才能维护对象数据");
        // 非应用成员不能登记收藏
        assertThatThrownBy(
                        () ->
                                marks.favorite(
                                        favorite(
                                                app, RECORD_ID, null, null, filesId, 930001L, true),
                                        20002))
                .hasMessageContaining("没有此应用的运行权限");
        // 位置与节点身份不匹配：登记失败，不产生标记
        assertThatThrownBy(
                        () ->
                                marks.favorite(
                                        favorite(
                                                null, RECORD_ID, null, null, filesId, 930002L,
                                                true),
                                        10001))
                .hasMessageContaining("记录不存在或不可访问");

        // 应用入口收藏成功后按当前授权链重新过滤：主表附件字段不可读的收藏消失；明细区授权按区域生效，行内附件仍可见
        assertThat(
                        marks.favorite(
                                favorite(app, RECORD_ID, null, null, filesId, 930001L, true),
                                10001))
                .isTrue();
        assertThat(
                        marks.markedFiles(
                                new BusinessFiles.MarkQuery(narrow, objectId, "FAVORITE"), 10001))
                .satisfies(
                        page -> {
                            assertThat(page.getTotal()).isEqualTo(1L);
                            assertThat(page.getList().getFirst().fileId()).isEqualTo(1003L);
                        });
        assertThat(
                        marks.markedFiles(
                                new BusinessFiles.MarkQuery(null, objectId, "FAVORITE"), 10001))
                .satisfies(
                        page -> {
                            assertThat(page.getTotal()).isEqualTo(2L);
                            assertThat(page.getList())
                                    .extracting(BusinessFiles.File::fileId)
                                    .containsExactly(1001L, 1003L);
                        });

        // 取消收藏后列表移除；最近访问独立登记并顺延访问时间，不覆盖收藏
        assertThat(
                        marks.favorite(
                                favorite(null, RECORD_ID, detailId, "b", docId, 930003L, false),
                                10001))
                .isFalse();
        assertThat(
                        marks.markedFiles(
                                new BusinessFiles.MarkQuery(null, objectId, "FAVORITE"), 10001))
                .satisfies(
                        page -> {
                            assertThat(page.getTotal()).isEqualTo(1L);
                            assertThat(page.getList().getFirst().fileId()).isEqualTo(1001L);
                        });
        marks.access(contentQuery(null, RECORD_ID, null, null, filesId, 930001L), 10001);
        marks.access(contentQuery(null, RECORD_ID, detailId, "b", docId, 930003L), 10001);
        assertThat(marks.markedFiles(new BusinessFiles.MarkQuery(null, objectId, "RECENT"), 10001))
                .satisfies(
                        page -> {
                            assertThat(page.getTotal()).isEqualTo(2L);
                            assertThat(page.getList())
                                    .extracting(BusinessFiles.File::fileId)
                                    .containsExactly(1003L, 1001L);
                        });
        // 再次访问顺延到最前；位置不匹配与无入口权限的登记静默跳过；空请求不报错
        marks.access(contentQuery(null, RECORD_ID, null, null, filesId, 930001L), 10001);
        marks.access(contentQuery(null, RECORD_ID, null, null, filesId, 930002L), 10001);
        marks.access(contentQuery(app, RECORD_ID, null, null, filesId, 930001L), 20002);
        marks.access(null, 10001);
        assertThat(marks.markedFiles(new BusinessFiles.MarkQuery(null, objectId, "RECENT"), 10001))
                .satisfies(
                        page -> {
                            assertThat(page.getTotal()).isEqualTo(2L);
                            assertThat(page.getList())
                                    .extracting(BusinessFiles.File::fileId)
                                    .containsExactly(1001L, 1003L);
                        });
        assertThat(
                        marks.markedFiles(
                                new BusinessFiles.MarkQuery(null, objectId, "FAVORITE"), 10001))
                .satisfies(page -> assertThat(page.getTotal()).isEqualTo(1L));

        // 标记窗口上限 100：真实收藏被更晚的不可见标记挤出窗口时不返回，重新靠前后仍按授权链返回
        for (int i = 0; i < 100; i++) {
            jdbc.update(
                    "INSERT INTO public.nocode_biz_file_mark (user_id, object_id, entry_id,"
                            + " mark_type, creator, updater) VALUES"
                            + " (10001,?,?,'FAVORITE','10001','10001')",
                    objectId,
                    950000L + i);
        }
        assertThat(
                        marks.markedFiles(
                                new BusinessFiles.MarkQuery(null, objectId, "FAVORITE"), 10001))
                .satisfies(page -> assertThat(page.getTotal()).isZero());
        jdbc.update(
                "UPDATE public.nocode_biz_file_mark SET create_time = now() + interval '1 minute'"
                        + " WHERE user_id=10001 AND object_id=? AND entry_id=930001 AND"
                        + " mark_type='FAVORITE' AND deleted=0",
                objectId);
        assertThat(
                        marks.markedFiles(
                                new BusinessFiles.MarkQuery(null, objectId, "FAVORITE"), 10001))
                .satisfies(
                        page -> {
                            assertThat(page.getTotal()).isEqualTo(1L);
                            assertThat(page.getList().getFirst().fileId()).isEqualTo(1001L);
                        });

        // 入参校验
        assertThatThrownBy(() -> marks.favorite(null, 10001)).hasMessageContaining("业务文件收藏请求不能为空");
        assertThatThrownBy(
                        () ->
                                marks.favorite(
                                        favorite(
                                                null, RECORD_ID, null, null, filesId, 930001L,
                                                null),
                                        10001))
                .hasMessageContaining("收藏状态不能为空");
        assertThatThrownBy(() -> marks.markedFiles(null, 10001))
                .hasMessageContaining("业务文件标记查询不能为空");
        assertThatThrownBy(
                        () ->
                                marks.markedFiles(
                                        new BusinessFiles.MarkQuery(null, "abc", "FAVORITE"),
                                        10001))
                .hasMessageContaining("数据对象标识无效");
        assertThatThrownBy(
                        () ->
                                marks.markedFiles(
                                        new BusinessFiles.MarkQuery(null, objectId, "STAR"), 10001))
                .hasMessageContaining("无效的 BusinessFileMarkTypeEnum 编码");
    }

    /** 发布带业务文件规则的对象：两层分组、记录标签字段、主表与明细各一个附件字段 */
    private void publishFixture() {
        publishFixture(List.of("new-title"), null);
    }

    private void publishFixture(List<String> labelFields, String titleTemplate) {
        publishFixture(labelFields, titleTemplate, false);
    }

    private void publishFixture(List<String> labelFields, String titleTemplate, boolean liveTitle) {
        List<FieldDefinition> main =
                new ArrayList<>(
                        List.of(
                                field("new-title", "name", "合同名称", "TEXT", 0),
                                field("dept", "dept", "归属部门", "TEXT", 1),
                                field("signed", "signed", "签订日期", "DATE", 2),
                                field("files", "files", "合同附件", "ATTACHMENT", 3)));
        if (liveTitle) {
            main.add(field("amount", "amount", "金额", "INTEGER", 4));
            main.add(field("live_total", "live_total", "动态标题", "FORMULA", 5));
        }
        SaveObjectDraft request =
                new SaveObjectDraft(
                        null,
                        null,
                        fixture.prefix + "browse",
                        "业务文件浏览验证",
                        null,
                        "biz_" + fixture.prefix + "browse",
                        "new-title",
                        main,
                        List.of());
        DataCenter.Detail items =
                new DataCenter.Detail(
                        null,
                        "items",
                        "明细区",
                        "biz_" + fixture.prefix + "items",
                        "ACTIVE",
                        List.of(
                                field("label", "label", "行说明", "TEXT", 0),
                                field("doc", "doc", "行附件", "ATTACHMENT", 1)),
                        Map.of(),
                        List.of());
        DataCenter.BusinessFilePolicy policy =
                new DataCenter.BusinessFilePolicy(
                        "浏览验证空间",
                        List.of("合同"),
                        List.of(
                                new DataCenter.BusinessFileGroup("dept", null),
                                new DataCenter.BusinessFileGroup("signed", "YEAR")),
                        labelFields,
                        List.of("files", "doc"));
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                request,
                                new DataCenter.Settings(
                                        null, null, null, titleTemplate, null, policy),
                                liveTitle
                                        ? Map.of(
                                                "live_total",
                                                DataCenter.FieldOptions.copyOf(
                                                                DataCenter.FieldOptions.defaults())
                                                        .expression("amount * 2")
                                                        .resultType("DECIMAL")
                                                        .calculation(
                                                                new CalculationOptions(
                                                                        "LOCAL", "LIVE", null, null,
                                                                        null, null, "AND",
                                                                        List.of(), false, List.of(),
                                                                        null))
                                                        .build())
                                        : null,
                                List.of(),
                                List.of(),
                                List.of(items)),
                        10001);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "业务文件浏览验证"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");

        objectId = design.draft().id();
        DataObjectApi.PublishedObject published = objects.getVersion(objectId, null);
        ruleVersion = published.versionNo();
        DataCenter.Definition definition = published.definition();
        detailId = definition.details().getFirst().id();
        DataCenter.BusinessFilePolicy saved = definition.settings().businessFilePolicy();
        Set<String> mainFields =
                definition.fields().stream()
                        .map(FieldDefinition::id)
                        .collect(java.util.stream.Collectors.toSet());
        Set<String> detailFields =
                definition.details().getFirst().fields().stream()
                        .map(FieldDefinition::id)
                        .collect(java.util.stream.Collectors.toSet());
        titleId = definition.titleFieldId();
        // 规则内的临时 key 已映射为稳定 ID：主表与明细字段各取一个
        assertThat(saved.fieldIds()).hasSize(2);
        filesId = saved.fieldIds().stream().filter(mainFields::contains).findFirst().orElseThrow();
        docId = saved.fieldIds().stream().filter(detailFields::contains).findFirst().orElseThrow();
        assertThat(definition.fields())
                .filteredOn(f -> f.id().equals(titleId))
                .singleElement()
                .satisfies(f -> assertThat(f.name()).isEqualTo("合同名称"));
        assertThat(definition.details().getFirst().fields())
                .filteredOn(f -> f.id().equals(docId))
                .singleElement()
                .satisfies(f -> assertThat(f.name()).isEqualTo("行附件"));
    }

    /** 记录目录绑定 + 主表字段与明细行附件绑定；绑定表不参与对象元数据清理，由本用例前缀清理 */
    private void insertBindings() {
        jdbc.update(
                "INSERT INTO public.\""
                        + mainTable()
                        + "\" (id, creator, create_time, updater, update_time, deleted, name, dept,"
                        + " signed) VALUES (?,?,now(),?,now(),0,?,?,?)",
                Long.valueOf(RECORD_ID),
                "10001",
                "10001",
                "采购合同A",
                "销售部",
                java.sql.Date.valueOf(LocalDate.of(2026, 3, 5)));
        jdbc.update(
                "INSERT INTO public.nocode_biz_directory_binding (object_id, record_id, space_id,"
                        + " entry_id, rule_version, group_keys, creator, updater) VALUES"
                        + " (?,?,?,?,?,?,?,?)",
                objectId,
                RECORD_ID,
                SPACE_ID,
                RECORD_ENTRY_ID,
                ruleVersion,
                GROUP_KEYS,
                "10001",
                "10001");
        attachment("", "", filesId, 1001L, 930001L, "合同扫描件.pdf", 2048L, 3);
        attachment(detailId, "a", docId, 1002L, 930002L, "盖章页.pdf", 1024L, 2);
        attachment(detailId, "b", docId, 1003L, 930003L, "附件说明.pdf", 512L, 1);
    }

    private void attachment(
            String detail,
            String row,
            String fieldId,
            long fileId,
            long entryId,
            String name,
            long size,
            int minutesAgo) {
        jdbc.update(
                "INSERT INTO public.nocode_biz_attachment_binding (object_id, record_id, detail_id,"
                        + " row_id, field_id, file_id, entry_id, space_id, state, file_name,"
                        + " file_size, mime_type, source_entry, creator, create_time, updater,"
                        + " update_time) VALUES (?,?,?,?,?,?,?,?,'ACTIVE',?,?,'application/pdf',"
                        + "'OBJECT_MAINTENANCE','10001', now() - make_interval(mins => ?),"
                        + " '10001', now())",
                objectId,
                RECORD_ID,
                detail,
                row,
                fieldId,
                fileId,
                entryId,
                SPACE_ID,
                name,
                size,
                minutesAgo);
    }

    /** 应用入口：附件字段与明细区可读，分组与记录标签来源字段不可读 */
    private String publishRestrictedApplication() {
        return publishApplication("app", "业务文件入口验证", Set.of(filesId));
    }

    /** 应用入口：按给定主表可读字段发布；明细区保持可读，用于验证字段级收窄 */
    private String publishApplication(String suffix, String name, Set<String> readFields) {
        DataObjectApi.PublishedObject version = objects.getVersion(objectId, null);
        var saved =
                apps.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + suffix,
                                name,
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        version.objectId(),
                                                        version.versionNo(),
                                                        version.checksum())),
                                        List.of())),
                        10001);
        String app = saved.application().id();
        int revision =
                sharing.forApplication(app).stream()
                        .filter(g -> g.objectId().equals(objectId))
                        .findFirst()
                        .orElseThrow()
                        .revision();
        sharing.save(
                new ObjectSharing.Save(
                        objectId,
                        app,
                        revision,
                        new ApplicationAuthorization.ObjectGrant(
                                objectId,
                                Set.of(ApplicationActionEnum.READ.getCode()),
                                "ALL",
                                readFields,
                                readFields,
                                Set.of(detailId),
                                Set.of(detailId),
                                Set.of(),
                                Set.of()),
                        "业务文件浏览受限授权夹具"),
                10001);
        apps.publish(new ApplicationCenter.Revision(app, 0, name), 10001);
        return app;
    }

    private BusinessFiles.ContentQuery contentQuery(
            String app,
            String recordId,
            String detailId,
            String rowId,
            String fieldId,
            Long entryId) {
        return new BusinessFiles.ContentQuery(
                app, objectId, recordId, detailId, rowId, fieldId, entryId);
    }

    private BusinessFiles.FavoriteQuery favorite(
            String app,
            String recordId,
            String detailId,
            String rowId,
            String fieldId,
            Long entryId,
            Boolean favorite) {
        return new BusinessFiles.FavoriteQuery(
                app, objectId, recordId, detailId, rowId, fieldId, entryId, favorite);
    }

    private BusinessFiles.DirectoryQuery directory(
            String app,
            Integer version,
            List<String> groupKeys,
            String recordId,
            String detailId,
            String rowId) {
        return new BusinessFiles.DirectoryQuery(
                app, objectId, version, groupKeys, recordId, detailId, rowId, 1, 20);
    }

    private BusinessFiles.FileQuery files(
            String app,
            Integer version,
            List<String> groupKeys,
            String recordId,
            String detailId,
            String rowId,
            String fieldId,
            String search) {
        return new BusinessFiles.FileQuery(
                app, objectId, version, groupKeys, recordId, detailId, rowId, fieldId, search, 1,
                20);
    }

    private FieldDefinition field(String key, String code, String name, String type, int sort) {
        return new FieldDefinition(
                key, null, code, name, type, null, null, null, false, false, sort);
    }

    private String mainTable() {
        return "biz_" + fixture.prefix + "browse";
    }

    private String md5(String value) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
