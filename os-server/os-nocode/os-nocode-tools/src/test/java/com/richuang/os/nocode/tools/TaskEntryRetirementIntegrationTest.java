package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationCenter.*;
import com.richuang.os.nocode.application.service.task.TaskEntryPolicyService;
import com.richuang.os.nocode.controller.admin.task.TaskEntryController;
import com.richuang.os.nocode.runtime.service.task.TaskEntryRuntimeService;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskCenterService;

import org.junit.jupiter.api.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;
import java.util.*;

/** 真实开发库验证旧入口冻结及新任务存量兼容，只种入并清理本测试的历史夹具。 */
class TaskEntryRetirementIntegrationTest {
    private static final long ACTOR = 10001L;
    private WorkDraftIntegrationTest business;
    private Resource legacy;
    private String root;
    private int legacyVersion;
    private TaskEntryPolicyService policies;

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
        business = new WorkDraftIntegrationTest();
        business.setup();
        Detail current = current();
        Set<String> fields = Set.of(business.nameField);
        ApplicationAuthorization.ObjectGrant grant =
                new ApplicationAuthorization.ObjectGrant(
                        business.object.objectId(),
                        Set.of("READ", "CREATE"),
                        "ALL",
                        fields,
                        fields,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of());
        TaskEntries.Config config =
                new TaskEntries.Config(
                        business.object.objectId(),
                        null,
                        business.resource.resourceId(),
                        "FORM",
                        "历史入口",
                        null,
                        null,
                        1,
                        List.of(grant));
        Resource entry =
                new Resource(
                        "legacy",
                        "TASK_ENTRY",
                        "legacy_entry",
                        "历史施工入口",
                        mapper.convertValue(config, new TypeReference<Map<String, Object>>() {}));
        List<Resource> resources = new ArrayList<>(current.draft().resources());
        resources.add(entry);
        Detail seeded =
                LegacyTaskEntryFixtures.seed(
                        business.applications, save(current, resources), ACTOR);
        business.applications.publish(
                new Revision(id(), seeded.application().revision(), "历史入口夹具"), ACTOR);
        legacyVersion = business.applications.published(id()).versionNo();
        legacy =
                current().draft().resources().stream()
                        .filter(r -> r.id().equals("legacy"))
                        .findFirst()
                        .orElseThrow();
        policies = servicesContext.getBean(TaskEntryPolicyService.class);
        policies.save(new TaskEntries.SavePolicy(id(), "legacy", 0, true, List.of()), ACTOR);
        AdminUserRespDTO user = new AdminUserRespDTO();
        user.setId(ACTOR);
        user.setNickname("历史入口测试负责人");
        user.setStatus(0);
        when(servicesContext.getBean(AdminUserApi.class).getUser(ACTOR)).thenReturn(user);
    }

    @AfterEach
    void cleanup() {
        if (root != null) {
            jdbc.update("DELETE FROM public.nocode_task_event WHERE root_id=?", root);
            jdbc.update("DELETE FROM public.nocode_task_instance WHERE root_id=?", root);
        }
        if (business != null && business.resource != null)
            jdbc.update(
                    "DELETE FROM public.nocode_task_entry_access WHERE application_id=?",
                    Long.parseLong(id()));
        if (business != null) business.cleanup();
    }

    @Test
    void cannotCreateOrAppendLegacyEntriesThroughApplicationSave() {
        Detail before = current();
        assertThatThrownBy(
                        () ->
                                business.applications.save(
                                        new Save(
                                                null,
                                                null,
                                                business.fixture.prefix + "newapp",
                                                "新建旧入口应失败",
                                                null,
                                                null,
                                                before.draft()),
                                        ACTOR))
                .hasMessageContaining("不能新增任务入口");
        List<Resource> changed = new ArrayList<>(before.draft().resources());
        changed.add(
                new Resource("another", "TASK_ENTRY", "another_entry", "另一个旧入口", legacy.config()));
        assertThatThrownBy(() -> business.applications.save(save(before, changed), ACTOR))
                .hasMessageContaining("不能新增任务入口");
        assertThat(current().draft()).isEqualTo(before.draft());
        assertThat(current().application().revision()).isEqualTo(before.application().revision());
    }

    @Test
    void rejectsLegacyRenameConfigKindAndRemovalWithoutChangingPersistedDraft() {
        Detail before = current();
        Map<String, Object> config = new LinkedHashMap<>(legacy.config());
        config.put("category", "被篡改分类");
        for (Resource changed :
                List.of(
                        new Resource(
                                legacy.id(), legacy.kind(), legacy.code(), "改名", legacy.config()),
                        new Resource(
                                legacy.id(),
                                legacy.kind(),
                                "changed_code",
                                legacy.name(),
                                legacy.config()),
                        new Resource(
                                legacy.id(), "FORM", legacy.code(), legacy.name(), legacy.config()),
                        new Resource(
                                legacy.id(),
                                legacy.kind(),
                                legacy.code(),
                                legacy.name(),
                                config))) {
            List<Resource> resources =
                    before.draft().resources().stream()
                            .map(resource -> resource.id().equals(legacy.id()) ? changed : resource)
                            .toList();
            assertThatThrownBy(() -> business.applications.save(save(before, resources), ACTOR))
                    .hasMessageContaining("不能修改存量入口配置");
        }
        List<Resource> withoutEntry =
                before.draft().resources().stream()
                        .filter(resource -> !resource.id().equals(legacy.id()))
                        .toList();
        assertThatThrownBy(() -> business.applications.save(save(before, withoutEntry), ACTOR))
                .hasMessageContaining("不能删除存量任务入口");
        assertThatThrownBy(
                        () ->
                                business.applications.save(
                                        new Save(
                                                id(),
                                                before.application().revision(),
                                                before.application().code(),
                                                before.application().name(),
                                                null,
                                                null,
                                                null),
                                        ACTOR))
                .hasMessageContaining("不能删除存量任务入口");
        assertThat(current().draft()).isEqualTo(before.draft());
        assertThat(current().application().revision()).isEqualTo(before.application().revision());
    }

    @Test
    void unchangedLegacyResourcesDoNotBlockOrdinaryEditingPublishingOrCompatibleRestore() {
        Published original = business.applications.published(id(), legacyVersion);
        Detail before = current();
        List<Resource> resources = new ArrayList<>(before.draft().resources());
        Resource form =
                resources.stream()
                        .filter(resource -> resource.kind().equals("FORM"))
                        .findFirst()
                        .orElseThrow();
        resources.add(new Resource("another-form", "FORM", "another_form", "新表单", form.config()));
        Collections.reverse(resources);
        Detail saved = business.applications.save(save(before, resources), ACTOR);
        Detail published =
                business.applications.publish(
                        new Revision(id(), saved.application().revision(), "编辑其他资源"), ACTOR);
        assertThat(published.draft().resources()).contains(legacy);
        assertThat(business.applications.published(id()).definition().resources())
                .hasSize(resources.size());
        business.applications.restore(
                new Restore(id(), published.application().revision(), legacyVersion, "恢复其他资源"),
                ACTOR);
        assertThat(business.applications.published(id()).definition())
                .isEqualTo(original.definition());
        assertThat(business.applications.published(id(), legacyVersion).checksum())
                .isEqualTo(original.checksum());
        assertThat(business.applications.published(id(), legacyVersion).definition())
                .isEqualTo(original.definition());
        assertThat(current().draft().resources()).hasSize(resources.size());
    }

    @Test
    void historicalRestoreCannotDeleteFrozenResourcesOrRewriteHistoricalSnapshots() {
        Published before = business.applications.published(id());
        Published first = business.applications.published(id(), 1);
        assertThatThrownBy(
                        () ->
                                business.applications.restore(
                                        new Restore(
                                                id(),
                                                current().application().revision(),
                                                1,
                                                "旧版本没有入口"),
                                        ACTOR))
                .hasMessageContaining("不能删除存量任务入口");
        assertThat(business.applications.published(id()).versionNo()).isEqualTo(before.versionNo());
        assertThat(business.applications.published(id(), 1).checksum()).isEqualTo(first.checksum());
    }

    @Test
    void existingEntryBoundTaskKeepsFormsFilteringAndRevocableAuthorization() {
        TaskCenterService tasks = servicesContext.getBean(TaskCenterService.class);
        TaskCenter.NodeInput node =
                new TaskCenter.NodeInput(
                        null,
                        null,
                        "存量施工任务",
                        null,
                        ACTOR,
                        null,
                        null,
                        null,
                        List.of(),
                        new TaskCenter.Binding(id(), business.resource.resourceId(), "legacy"),
                        null);
        TaskCenter.Detail created =
                tasks.create(
                        new TaskCenter.Create(node, null, null, null, null, null, null, key()),
                        ACTOR);
        root = created.task().id();
        assertThat(tasks.entryOptions(ACTOR))
                .extracting(TaskCenter.EntryOption::value)
                .contains(id() + ":ENTRY:legacy");
        tasks.transition(
                new TaskCenter.Transition(
                        root, created.task().revision(), TaskCenter.Action.START, null, key()),
                ACTOR);
        Detail application = current();
        business.applications.publish(
                new Revision(id(), application.application().revision(), "存量入口原样再发布"), ACTOR);
        ApplicationRecords.Save record =
                new ApplicationRecords.Save(
                        id(),
                        business.object.objectId(),
                        null,
                        null,
                        Map.of(business.nameField, "已有任务正常填写"),
                        Map.of(),
                        Map.of(),
                        null,
                        business.resource.resourceId(),
                        key(),
                        null);
        TaskCenter.FormContext saved =
                tasks.saveBusiness(
                        new TaskCenter.SaveBusiness(
                                root, tasks.detail(root, ACTOR).task().revision(), record),
                        ACTOR);
        assertThat(saved.record().record().values()).containsEntry(business.nameField, "已有任务正常填写");
        assertThat(saved.binding().resource().applicationVersion()).isEqualTo(legacyVersion);
        TaskEntries.Policy policy = policies.get(id(), "legacy");
        policies.save(
                new TaskEntries.SavePolicy(
                        id(), "legacy", policy.revision(), false, policy.members()),
                ACTOR);
        assertThatThrownBy(() -> tasks.form(root, ACTOR)).hasMessageContaining("停用");
        assertThat(tasks.entryOptions(ACTOR))
                .extracting(TaskCenter.EntryOption::value)
                .doesNotContain(id() + ":ENTRY:legacy");
    }

    @Test
    void portalOnlyHttpMappingsAreRetiredWhileSharedExecutionMappingsRemain() {
        Set<String> paths = new HashSet<>();
        for (Method method : TaskEntryController.class.getDeclaredMethods()) {
            GetMapping get = method.getAnnotation(GetMapping.class);
            PostMapping post = method.getAnnotation(PostMapping.class);
            if (get != null) paths.addAll(List.of(get.value()));
            if (post != null) paths.addAll(List.of(post.value()));
        }
        assertThat(paths).doesNotContain("/mine", "/drafts", "/activity");
        assertThat(paths)
                .contains(
                        "/context",
                        "/get",
                        "/submit",
                        "/draft",
                        "/draft/save",
                        "/policy",
                        "/related-form",
                        "/selection");
        assertThat(
                        servicesContext
                                .getBean(TaskEntryRuntimeService.class)
                                .context(
                                        new TaskEntries.Locator(id(), "legacy", legacyVersion),
                                        ACTOR)
                                .config()
                                .formId())
                .isEqualTo(business.resource.resourceId());
    }

    private String id() {
        return business.resource.applicationId();
    }

    private Detail current() {
        return business.applications.get(id());
    }

    private Save save(Detail application, List<Resource> resources) {
        return new Save(
                application.application().id(),
                application.application().revision(),
                application.application().code(),
                application.application().name(),
                application.application().description(),
                application.application().icon(),
                new Definition(application.draft().objects(), resources));
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
