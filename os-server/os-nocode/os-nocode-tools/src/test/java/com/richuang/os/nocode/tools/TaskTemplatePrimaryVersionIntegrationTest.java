package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.module.msg.api.IMsgSendService;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.NocodeErrorCodes;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.runtime.dal.mapper.TaskLaunchDraftMapper;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskCenterService;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskLaunchDraftService;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskLaunchDraftServiceImpl;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.*;

/** 开发库验证主版本、固定快照、发起草稿与并发边界；只清理本类登记的随机夹具。 */
class TaskTemplatePrimaryVersionIntegrationTest {
    private static final long OWNER = 10001L, OTHER = 21001L, ADMIN = 31001L;
    private static AnnotationConfigApplicationContext draftContext;
    private static TaskLaunchDraftService drafts;
    private TaskCenterService tasks;
    private final String marker = "primary_version_" + UUID.randomUUID() + "_";
    private final Set<String> templates = new LinkedHashSet<>(),
            roots = new LinkedHashSet<>(),
            draftIds = new LinkedHashSet<>();

    @BeforeAll
    static void open() throws Exception {
        // 本测试要求主版本迁移（统一序列 V075）已应用，不在测试中自动升级开发库。
        connect();
        draftContext = new AnnotationConfigApplicationContext();
        draftContext.setParent(servicesContext);
        draftContext.registerBean(
                TaskLaunchDraftMapper.class, () -> session.getMapper(TaskLaunchDraftMapper.class));
        draftContext.register(TaskLaunchDraftServiceImpl.class);
        draftContext.refresh();
        drafts = draftContext.getBean(TaskLaunchDraftService.class);
    }

    @AfterAll
    static void end() {
        if (draftContext != null) draftContext.close();
        close();
    }

    @BeforeEach
    void setup() {
        tasks = servicesContext.getBean(TaskCenterService.class);
        Mockito.when(servicesContext.getBean(IMsgSendService.class).send(Mockito.any()))
                .thenReturn(999L);
        Mockito.when(servicesContext.getBean(AdminUserApi.class).getUser(Mockito.anyLong()))
                .thenAnswer(
                        i -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(i.getArgument(0));
                            user.setNickname("主版本验证" + user.getId());
                            user.setStatus(0);
                            return user;
                        });
        Mockito.when(
                        servicesContext
                                .getBean(PermissionCommonApi.class)
                                .hasAnyPermissions(ADMIN, "nocode:task:manage-all"))
                .thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        for (String id : draftIds)
            jdbc.update(
                    "delete from public.nocode_task_launch_draft where id=? and creator=?",
                    id,
                    Long.toString(OWNER));
        for (String root : roots) {
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from public.nocode_task_instance where id=?"
                                            + " and title like ?",
                                    Integer.class,
                                    root,
                                    marker + "%"))
                    .isEqualTo(1);
            jdbc.update("delete from public.nocode_task_event where root_id=?", root);
            jdbc.update("delete from public.nocode_task_instance where root_id=?", root);
        }
        for (String id : templates) {
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from public.nocode_task_template where id=?"
                                            + " and name like ?",
                                    Integer.class,
                                    id,
                                    marker + "%"))
                    .isEqualTo(1);
            jdbc.update("delete from public.nocode_task_template_version where template_id=?", id);
            jdbc.update(
                    "delete from public.nocode_task_template where id=? and name like ?",
                    id,
                    marker + "%");
        }
    }

    @Test
    void firstPublicationBecomesPrimaryEvenWhenNotRequestedAndHistoryIsComplete() {
        Template initial = save(null, "v1", List.of(node("a")));
        assertThat(initial.primaryVersion()).isNull();
        assertThat(tasks.versions(initial.id(), OWNER)).isEmpty();
        tasks.publish(new PublishTemplate(initial.id(), initial.revision(), false), OWNER);
        Template first = head(initial.id());
        assertThat(first.publishedVersion()).isEqualTo(1);
        assertThat(first.primaryVersion()).isEqualTo(1);
        TemplateVersion v1 = tasks.version(first.id(), 1, OWNER);
        Template edited = save(first, "v2", List.of(node("a"), node("b")));
        tasks.publish(new PublishTemplate(edited.id(), edited.revision(), false), OWNER);
        assertThat(head(first.id()).publishedVersion()).isEqualTo(2);
        assertThat(head(first.id()).primaryVersion()).isEqualTo(1);
        assertThat(tasks.version(first.id(), 1, OWNER)).isEqualTo(v1);
        List<TemplateVersionSummary> versions = tasks.versions(first.id(), OTHER);
        assertThat(versions).extracting(TemplateVersionSummary::version).containsExactly(2, 1);
        assertThat(versions).extracting(TemplateVersionSummary::nodeCount).containsExactly(2, 1);
        assertThat(versions)
                .extracting(TemplateVersionSummary::primary)
                .containsExactly(false, true);
        assertThat(versions)
                .extracting(TemplateVersionSummary::name)
                .containsExactly(marker + "v2", marker + "v1");
        assertThat(versions).allSatisfy(version -> assertThat(version.publishedAt()).isNotNull());
    }

    @Test
    void primarySwitchPreservesUnpublishedDraftSnapshotsAndAlreadyCreatedInstances() {
        Template first = publish(null, "v1", List.of(node("a")), true);
        Detail old = create(first.id(), 1, "old");
        Template second = publish(first, "v2", List.of(node("b")), true);
        Template unsavedVersion = save(second, "editing", List.of(node("not-published")));
        Map<String, Object> draftBefore = draftColumns(first.id());
        List<Map<String, Object>> snapshotsBefore = snapshots(first.id());
        Detail existingBefore = tasks.detail(old.task().id(), OWNER);
        Template switched =
                tasks.setPrimaryVersion(
                        new SetPrimaryTemplateVersion(first.id(), 1, unsavedVersion.revision()),
                        OWNER);
        assertThat(switched.primaryVersion()).isEqualTo(1);
        assertThat(switched.publishedVersion()).isEqualTo(2);
        assertThat(switched.revision()).isEqualTo(unsavedVersion.revision() + 1);
        assertThat(switched.name()).isEqualTo(marker + "editing");
        assertThat(draftColumns(first.id())).isEqualTo(draftBefore);
        assertThat(snapshots(first.id())).isEqualTo(snapshotsBefore);
        assertThat(tasks.detail(old.task().id(), OWNER)).isEqualTo(existingBefore);
        assertThat(
                        tasks.templates(OTHER).stream()
                                .filter(t -> t.id().equals(first.id()))
                                .findFirst()
                                .orElseThrow()
                                .name())
                .isEqualTo(marker + "v1");
    }

    @Test
    void defaultLaunchUsesPrimaryWhileExplicitHistoricalVersionStaysSelectable() {
        Template first = publish(null, "v1", List.of(node("old-child")), true);
        Template second = publish(first, "v2", List.of(node("new-child")), true);
        Template primary =
                tasks.setPrimaryVersion(
                        new SetPrimaryTemplateVersion(second.id(), 1, second.revision()), OWNER);
        assertThat(tasks.version(primary.id(), null, OWNER).version()).isEqualTo(1);
        Detail defaults = create(primary.id(), null, "default-old");
        Detail explicitNew = create(primary.id(), 2, "explicit-new");
        assertThat(defaults.task().templateVersion()).isEqualTo(1);
        assertThat(defaults.nodes())
                .extracting(Row::title)
                .contains(marker + "old-child")
                .doesNotContain(marker + "new-child");
        assertThat(explicitNew.task().templateVersion()).isEqualTo(2);
        assertThat(explicitNew.nodes())
                .extracting(Row::title)
                .contains(marker + "new-child")
                .doesNotContain(marker + "old-child");
        Template latest =
                tasks.setPrimaryVersion(
                        new SetPrimaryTemplateVersion(primary.id(), 2, primary.revision()), OWNER);
        assertThat(create(latest.id(), 1, "explicit-old").task().templateVersion()).isEqualTo(1);
        assertThat(create(latest.id(), null, "default-new").task().templateVersion()).isEqualTo(2);
    }

    @Test
    void publicationNumbersRemainMonotonicAndLegacyPublishDefaultsToNewPrimary() {
        Template first = publish(null, "v1", List.of(node("a")), true);
        Template second = publish(first, "v2", List.of(node("b")), true);
        Template olderPrimary =
                tasks.setPrimaryVersion(
                        new SetPrimaryTemplateVersion(second.id(), 1, second.revision()), OWNER);
        Template third = publish(olderPrimary, "v3", List.of(node("c")), false);
        assertThat(third.publishedVersion()).isEqualTo(3);
        assertThat(third.primaryVersion()).isEqualTo(1);
        Template fourthDraft = save(third, "v4", List.of(node("d")));
        tasks.publish(new PublishTemplate(fourthDraft.id(), fourthDraft.revision()), OWNER);
        Template fourth = head(third.id());
        assertThat(fourth.publishedVersion()).isEqualTo(4);
        assertThat(fourth.primaryVersion()).isEqualTo(4);
        assertThat(tasks.versions(third.id(), OWNER))
                .extracting(TemplateVersionSummary::version)
                .containsExactly(4, 3, 2, 1);
    }

    @Test
    void staleRevisionRejectedEvenForNoopAndSwitchCannotOverwriteSavedDraft() {
        Template first = publish(null, "v1", List.of(node("a")), true);
        Template second = publish(first, "v2", List.of(node("b")), true);
        Template current =
                tasks.setPrimaryVersion(
                        new SetPrimaryTemplateVersion(second.id(), 1, second.revision()), OWNER);
        assertThatThrownBy(
                        () ->
                                tasks.setPrimaryVersion(
                                        new SetPrimaryTemplateVersion(
                                                second.id(), 1, second.revision()),
                                        OWNER))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(NocodeErrorCodes.CONFLICT));
        Template unchanged =
                tasks.setPrimaryVersion(
                        new SetPrimaryTemplateVersion(current.id(), 1, current.revision()), OWNER);
        assertThat(unchanged.revision()).isEqualTo(current.revision());
        Template edited = save(current, "concurrent-draft", List.of(node("editing")));
        assertThatThrownBy(
                        () ->
                                tasks.setPrimaryVersion(
                                        new SetPrimaryTemplateVersion(
                                                current.id(), 2, current.revision()),
                                        OWNER))
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        error -> assertThat(error.getCode()).isEqualTo(NocodeErrorCodes.CONFLICT));
        assertThat(head(current.id())).isEqualTo(edited);
    }

    @Test
    void ownershipInvalidVersionAndPrivateDraftAreEnforced() {
        Template first = publish(null, "v1", List.of(node("a")), true);
        Template second = publish(first, "v2", List.of(node("b")), true);
        Map<String, Object> before = templateRow(first.id());
        assertThatThrownBy(
                        () ->
                                tasks.setPrimaryVersion(
                                        new SetPrimaryTemplateVersion(
                                                second.id(), 1, second.revision()),
                                        OTHER))
                .hasMessageContaining("只能维护自己创建");
        assertThatThrownBy(
                        () ->
                                tasks.setPrimaryVersion(
                                        new SetPrimaryTemplateVersion(
                                                second.id(), 99, second.revision()),
                                        OWNER))
                .hasMessageContaining("模板发布版本不存在");
        assertThatThrownBy(
                        () ->
                                tasks.setPrimaryVersion(
                                        new SetPrimaryTemplateVersion(
                                                second.id(), 0, second.revision()),
                                        OWNER))
                .hasMessageContaining("正整数");
        assertThatThrownBy(() -> tasks.version(second.id(), 99, OWNER))
                .hasMessageContaining("模板发布版本不存在");
        assertThatThrownBy(() -> tasks.create(command(second.id(), 99, "bad"), OWNER))
                .hasMessageContaining("模板发布版本不存在");
        assertThat(templateRow(first.id())).isEqualTo(before);
        Template switched =
                tasks.setPrimaryVersion(
                        new SetPrimaryTemplateVersion(second.id(), 1, second.revision()), ADMIN);
        assertThat(switched.primaryVersion()).isEqualTo(1);
        Template privateDraft = save(null, "private", List.of(node("secret")));
        assertThatThrownBy(() -> tasks.versions(privateDraft.id(), OTHER))
                .hasMessageContaining("只能维护自己创建");
        assertThat(tasks.versions(privateDraft.id(), OWNER)).isEmpty();
        assertThatThrownBy(
                        () ->
                                tasks.setPrimaryVersion(
                                        new SetPrimaryTemplateVersion(
                                                privateDraft.id(), 1, privateDraft.revision()),
                                        OWNER))
                .hasMessageContaining("模板发布版本不存在");
    }

    @Test
    void savedLaunchDraftKeepsItsSelectedVersionAfterPrimarySwitch() {
        Template first = publish(null, "v1", List.of(node("old-child")), true);
        Draft saved =
                drafts.save(new DraftSave(null, null, command(first.id(), 1, "draft-root")), OWNER);
        draftIds.add(saved.id());
        Template second = publish(first, "v2", List.of(node("new-child")), true);
        assertThat(tasks.version(second.id(), null, OWNER).version()).isEqualTo(2);
        Draft restored = drafts.get(saved.id(), OWNER);
        assertThat(restored.content()).isEqualTo(saved.content());
        Detail result =
                drafts.publish(new DraftPublish(saved.id(), saved.revision(), key()), OWNER);
        roots.add(result.task().id());
        assertThat(result.task().templateVersion()).isEqualTo(1);
        assertThat(result.nodes())
                .extracting(Row::title)
                .contains(marker + "old-child")
                .doesNotContain(marker + "new-child");
    }

    @Test
    void legacyNullPrimaryFallsBackToLatestAndCannotMoveNewSequenceBackwards() {
        Template first = publish(null, "v1", List.of(node("a")), true);
        Template second = publish(first, "v2", List.of(node("b")), true);
        jdbc.update(
                "update public.nocode_task_template set primary_version=null where id=?",
                second.id());
        assertThat(tasks.version(second.id(), null, OWNER).version()).isEqualTo(2);
        assertThat(head(second.id()).primaryVersion()).isEqualTo(2);
        assertThat(create(second.id(), null, "legacy-default").task().templateVersion())
                .isEqualTo(2);
        Template third = publish(head(second.id()), "v3", List.of(node("c")), false);
        assertThat(third.primaryVersion()).isEqualTo(2);
        assertThat(third.publishedVersion()).isEqualTo(3);
    }

    private Template save(Template previous, String title, List<NodeInput> nodes) {
        Template saved =
                tasks.saveTemplate(
                        new SaveTemplate(
                                previous == null ? null : previous.id(),
                                previous == null ? null : previous.revision(),
                                marker + title,
                                marker + title + "说明",
                                nodes,
                                Kind.ORDINARY,
                                node("root")),
                        OWNER);
        templates.add(saved.id());
        return saved;
    }

    private Template publish(
            Template previous, String title, List<NodeInput> nodes, boolean primary) {
        Template saved = save(previous, title, nodes);
        tasks.publish(new PublishTemplate(saved.id(), saved.revision(), primary), OWNER);
        return head(saved.id());
    }

    private Template head(String id) {
        return tasks.templates(OWNER).stream()
                .filter(template -> template.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private Create command(String template, Integer version, String title) {
        return new Create(node(title), null, template, version, null, null, null, key());
    }

    private Detail create(String template, Integer version, String title) {
        Detail result = tasks.create(command(template, version, title), OWNER);
        roots.add(result.task().id());
        return result;
    }

    private NodeInput node(String id) {
        return new NodeInput(
                id,
                null,
                marker + id,
                null,
                OWNER,
                Urgency.NORMAL,
                Priority.MEDIUM,
                new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                List.of(),
                null,
                null,
                null,
                AssignmentMode.ASSIGNED,
                List.of());
    }

    private Map<String, Object> draftColumns(String id) {
        return jdbc.queryForMap(
                "select name,description,kind,nodes_json,root_json,authorization_json"
                        + " from public.nocode_task_template where id=?",
                id);
    }

    private Map<String, Object> templateRow(String id) {
        return jdbc.queryForMap("select * from public.nocode_task_template where id=?", id);
    }

    private List<Map<String, Object>> snapshots(String id) {
        return jdbc.queryForList(
                "select * from public.nocode_task_template_version where template_id=?"
                        + " order by version_no",
                id);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
