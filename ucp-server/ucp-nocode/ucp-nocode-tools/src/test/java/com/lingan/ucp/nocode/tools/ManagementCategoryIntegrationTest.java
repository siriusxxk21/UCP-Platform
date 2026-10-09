package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.module.system.enums.permission.RoleCodeEnum;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationCenter.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.web.ObjectImportService;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 当前开发库分类回归；每例仅创建独立夹具，包含 DDL 的发布用例也整笔回滚。 */
class ManagementCategoryIntegrationTest extends NocodeIntegrationSupport {
    private ApplicationService applications() {
        return servicesContext.getBean(ApplicationService.class);
    }

    private void rollback(Runnable action) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            try {
                                action.run();
                            } finally {
                                tx.setRollbackOnly();
                            }
                        });
    }

    private SaveObjectDraft category(SaveObjectDraft request, String category) {
        return new SaveObjectDraft(
                request.id(),
                request.expectedLockVersion(),
                request.objectCode(),
                request.objectName(),
                request.description(),
                request.tableName(),
                request.titleFieldKey(),
                request.fields(),
                request.removedFieldIds(),
                request.titleTemplate(),
                category);
    }

    private DataCenter.Design saveObject(SaveObjectDraft request) {
        return designs.save(
                new DataCenter.SaveDesign(request, null, null, null, null, null), 10001);
    }

    private Detail createApplication(String suffix, String category, long actor) {
        return applications()
                .save(
                        new Save(
                                null,
                                null,
                                prefix + suffix,
                                "分类回归",
                                null,
                                null,
                                Definition.empty(),
                                category),
                        actor);
    }

    private Detail updateApplication(Detail detail, String category) {
        var a = detail.application();
        return applications()
                .save(
                        new Save(
                                a.id(),
                                a.revision(),
                                a.code(),
                                a.name(),
                                a.description(),
                                a.icon(),
                                detail.draft(),
                                category),
                        10001);
    }

    @Test
    void objectCategoriesPersistFilterAcrossPagesAndPreserveOmittedValues() {
        rollback(
                () -> {
                    String a = prefix + "_采购%_!";
                    String b = prefix + "_销售";
                    var first = saveObject(category(createRequest("one"), "  " + a + "  "));
                    saveObject(category(createRequest("two"), a));
                    saveObject(category(createRequest("three"), b));
                    saveObject(createRequest("empty"));
                    assertThat(first.draft().category()).isEqualTo(a);
                    var page = designs.page(1, 1, null, prefix, null, null, null, a);
                    assertThat(page.getTotal()).isEqualTo(2);
                    assertThat(page.getList())
                            .singleElement()
                            .extracting(DataCenter.ObjectRow::category)
                            .isEqualTo(a);
                    assertThat(designs.categories())
                            .contains(a, b)
                            .doesNotHaveDuplicates()
                            .doesNotContain("");
                    assertThat(designs.page(2, 1, null, prefix, null, null, null, a).getList())
                            .hasSize(1);
                    assertThat(designs.page(1, 10, null, prefix, null, null, null, "").getTotal())
                            .isEqualTo(1);
                    assertThat(designs.page(1, 10, null, prefix, null, null, null).getTotal())
                            .isEqualTo(4);
                    var preserved =
                            saveObject(
                                    edit(
                                            first.draft(),
                                            List.of(),
                                            List.of(),
                                            first.draft().titleFieldId()));
                    assertThat(preserved.draft().category()).isEqualTo(a);
                    var cleared =
                            saveObject(
                                    category(
                                            edit(
                                                    preserved.draft(),
                                                    List.of(),
                                                    List.of(),
                                                    preserved.draft().titleFieldId()),
                                            " \t "));
                    assertThat(cleared.draft().category()).isEmpty();
                });
    }

    @Test
    void applicationCategoriesFollowDesignerVisibilityAndPreserveOmittedValues() {
        rollback(
                () -> {
                    String own = prefix + "_我方";
                    String foreign = prefix + "_他方";
                    var first = createApplication("own", "  " + own + "  ", 10001);
                    createApplication("duplicate", own, 10001);
                    createApplication("foreign", foreign, 10002);
                    createApplication("empty", null, 10001);
                    assertThat(first.application().category()).isEqualTo(own);
                    assertThat(applications().categories(10001))
                            .contains(own)
                            .doesNotContain(foreign, "")
                            .doesNotHaveDuplicates();
                    assertThat(applications().page(1, 1, prefix, own, 10001L).getTotal())
                            .isEqualTo(2);
                    assertThat(applications().page(2, 1, prefix, own, 10001L).getList()).hasSize(1);
                    assertThat(applications().page(1, 10, prefix, foreign, 10001L).getTotal())
                            .isZero();
                    var permissions = servicesContext.getBean(PermissionCommonApi.class);
                    when(permissions.hasAnyRoles(10003L, RoleCodeEnum.SUPER_ADMIN.getCode()))
                            .thenReturn(true);
                    assertThat(applications().categories(10003)).contains(own, foreign);
                    assertThat(applications().page(1, 10, prefix, foreign, 10003L).getTotal())
                            .isEqualTo(1);
                    var preserved = updateApplication(first, null);
                    assertThat(preserved.application().category()).isEqualTo(own);
                    assertThat(updateApplication(preserved, " ").application().category())
                            .isEmpty();
                    assertThat(applications().page(1, 10, prefix, "", 10001L).getTotal())
                            .isEqualTo(2);
                });
    }

    @Test
    void copyAndStructureImportCarryCategoryWithoutVersionDefinitionFields() {
        rollback(
                () -> {
                    String value = prefix + "_分类";
                    var source = saveObject(category(createRequest("source"), value));
                    var copy =
                            designs.copy(
                                    new DataCenter.Copy(
                                            source.draft().id(),
                                            prefix + "copy",
                                            "分类副本",
                                            "biz_" + prefix + "copy"),
                                    10001);
                    assertThat(copy.draft().category()).isEqualTo(value);
                    var imported =
                            servicesContext
                                    .getBean(ObjectImportService.class)
                                    .create(
                                            new DataCenter.ImportDesign(
                                                    prefix + "import",
                                                    "分类导入",
                                                    "biz_" + prefix + "import",
                                                    "name",
                                                    List.of(
                                                            new DataCenter.ImportColumn(
                                                                    "name", "名称", "TEXT", 200, null,
                                                                    null, true, false)),
                                                    value),
                                            10001);
                    assertThat(imported.draft().category()).isEqualTo(value);
                    assertThat(designs.version(source.draft().id(), 1))
                            .doesNotContain("\"category\"");
                });
    }

    @Test
    void categoryLengthAndOptimisticLockUseExistingBusinessErrors() {
        rollback(
                () -> {
                    assertThat(
                                    saveObject(category(createRequest("limit"), "分".repeat(100)))
                                            .draft()
                                            .category())
                            .hasSize(100);
                    assertThatThrownBy(
                                    () ->
                                            saveObject(
                                                    category(
                                                            createRequest("long"),
                                                            "分".repeat(101))))
                            .isInstanceOf(ServiceException.class);
                });
        rollback(
                () ->
                        assertThatThrownBy(() -> createApplication("long", "分".repeat(101), 10001))
                                .isInstanceOf(ServiceException.class));
        rollback(
                () -> {
                    var original = createApplication("lock", "甲", 10001);
                    updateApplication(original, "乙");
                    assertThatThrownBy(() -> updateApplication(original, "丙"))
                            .isInstanceOf(ServiceException.class);
                    assertThat(
                                    applications()
                                            .get(original.application().id())
                                            .application()
                                            .category())
                            .isEqualTo("乙");
                });
    }

    @Test
    void publishedObjectAndApplicationSnapshotsRemainUnchangedByCategoryEdits() {
        rollback(
                () -> {
                    var object = saveObject(category(createRequest("published"), "甲"));
                    var plan =
                            publisher.plan(
                                    new DataCenter.Revision(
                                            object.draft().id(),
                                            object.draft().lockVersion(),
                                            null),
                                    10001);
                    assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
                    assertThat(
                                    publisher
                                            .execute(
                                                    new DataCenter.ExecutePlan(plan.id(), "分类回归"),
                                                    10001)
                                            .state())
                            .isEqualTo("SUCCEEDED");
                    var published =
                            servicesContext
                                    .getBean(DataObjectApi.class)
                                    .getVersion(object.draft().id(), 1);
                    var app =
                            applications()
                                    .save(
                                            new Save(
                                                    null,
                                                    null,
                                                    prefix + "publishedapp",
                                                    "分类发布",
                                                    null,
                                                    null,
                                                    new Definition(
                                                            List.of(
                                                                    new ObjectReference(
                                                                            published.objectId(),
                                                                            published.versionNo(),
                                                                            published.checksum())),
                                                            List.of()),
                                                    "甲"),
                                            10001);
                    grantApplicationObjects(app.application().id());
                    app =
                            applications()
                                    .publish(
                                            new Revision(
                                                    app.application().id(),
                                                    app.application().revision(),
                                                    "分类回归"),
                                            10001);
                    String appSnapshot =
                            jdbc.queryForObject(
                                    "SELECT definition_json::text FROM"
                                            + " public.nocode_application_version WHERE"
                                            + " application_id=? AND version_no=1",
                                    String.class,
                                    Long.valueOf(app.application().id()));
                    var updated = updateApplication(app, "乙");
                    assertThat(updated.application().category()).isEqualTo("乙");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT definition_json::text FROM"
                                                    + " public.nocode_application_version WHERE"
                                                    + " application_id=? AND version_no=1",
                                            String.class,
                                            Long.valueOf(app.application().id())))
                            .isEqualTo(appSnapshot)
                            .doesNotContain("\"category\"");
                    var head = designs.get(object.draft().id());
                    var draft =
                            designs.editPublished(
                                    new DataCenter.Revision(
                                            head.draft().id(), head.draft().lockVersion(), null),
                                    10001);
                    saveObject(
                            category(
                                    edit(
                                            draft.draft(),
                                            List.of(),
                                            List.of(),
                                            draft.draft().titleFieldId()),
                                    "乙"));
                    assertThat(
                                    servicesContext
                                            .getBean(DataObjectApi.class)
                                            .getVersion(object.draft().id(), 1)
                                            .checksum())
                            .isEqualTo(published.checksum());
                    assertThat(designs.get(object.draft().id()).draft().category()).isEqualTo("乙");
                });
    }
}
