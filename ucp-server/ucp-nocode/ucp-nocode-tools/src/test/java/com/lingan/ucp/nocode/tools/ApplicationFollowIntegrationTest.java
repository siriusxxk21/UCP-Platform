package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.Member;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.ObjectGrant;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.security.access.AccessDeniedException;

import java.util.*;

/**
 * 应用自动跟随对象最新版本（契约第 5、6 章）。对象「甲」被应用 A、B、C 引用；每条用例都读库核对应用版本表、发布指针、草稿、跟随状态行与日志行， 不只看接口返回。
 *
 * <p>对象发布（对旧应用兼容的改动）在同一事务里替开着开关的应用做「同步 + 发布」；单个应用的失败用保存点隔离，不影响对象发布和其它应用。
 * 进了暂停名单的应用（不兼容改动）不跟，流程与没有这个功能时相同。
 */
class ApplicationFollowIntegrationTest {
    private static final Set<String> ALL = Set.of("*");
    private FollowFixture f;
    private String jia;
    private String a, b, c;

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
        Mockito.when(
                        servicesContext
                                .getBean(PermissionCommonApi.class)
                                .hasAnyPermissions(10001L, "nocode:app:manage"))
                .thenReturn(true);
        f = new FollowFixture();
        jia = f.object("jia", "甲");
        a = f.app("a", "应用A", jia);
        b = f.app("b", "应用B", jia);
        c = f.app("c", "应用C", jia);
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    private void assertFollowed(String app, String planId, int applicationVersion) {
        assertThat(f.publishedVersion(app)).isEqualTo(applicationVersion);
        ApplicationCenter.ObjectReference latest = f.reference(jia);
        assertThat(f.publishedReference(app, jia).path("versionNo").asInt())
                .isEqualTo(latest.versionNo());
        assertThat(f.publishedReference(app, jia).path("checksum").asText())
                .isEqualTo(latest.checksum());
        assertThat(f.draftReference(app, jia).path("versionNo").asInt())
                .isEqualTo(latest.versionNo());
        assertThat(f.draftReference(app, jia).path("checksum").asText())
                .isEqualTo(latest.checksum());
        Map<String, Object> state = f.state(app, jia);
        assertThat(state).containsEntry("state", "FOLLOWING").containsEntry("enabled", true);
        assertThat(state.get("followed_version")).isEqualTo(latest.versionNo());
        assertThat(state.get("pending_code")).isNull();
        Map<String, Object> log = f.logs(app, jia).getLast();
        assertThat(log)
                .containsEntry("outcome", "FOLLOWED")
                .containsEntry("to_version", latest.versionNo())
                .containsEntry("application_version_after", applicationVersion)
                .containsEntry("plan_id", planId);
    }

    private void assertUntouched(String app, int applicationVersion, int objectVersion) {
        assertThat(f.publishedVersion(app)).isEqualTo(applicationVersion);
        assertThat(f.versionCount(app)).isEqualTo(applicationVersion);
        assertThat(f.publishedReference(app, jia).path("versionNo").asInt())
                .isEqualTo(objectVersion);
    }

    // ── 5.2-1、5.2-2 ──

    /** 给甲加普通字段并发布：三个应用各多一个版本，发布说明逐字，快照与草稿都指到新版本，各一行带计划号的 FOLLOWED 日志。没有人去应用里点任何东西。 */
    @Test
    void compatiblePublishMovesEveryFollowingApplication() {
        String planId = f.publishNewField(jia, "note", "增加备注二");
        for (String app : List.of(a, b, c)) {
            assertFollowed(app, planId, 2);
            assertThat(f.versionCount(app)).isEqualTo(2);
            assertThat(f.publishReason(app)).isEqualTo("系统跟随：对象「甲」V1 → V2。增加备注二");
            assertThat(f.logs(app, jia)).hasSize(1);
            assertThat(f.logs(app, jia).getFirst())
                    .containsEntry("from_version", 1)
                    .containsEntry("application_version_before", 1)
                    .containsEntry("trigger_kind", "OBJECT_PUBLISH")
                    .containsEntry("creator", "10001");
            assertThat(f.dependencyRows(app, "published")).isEqualTo(1);
        }
        // 运行端下一次请求就读到新版本，新字段在应用固定的对象版本里。
        assertThat(f.apps.published(a).versionNo()).isEqualTo(2);
        assertThat(f.follows.result(planId))
                .extracting(ApplicationFollows.FollowResult::applicationId)
                .containsExactly(a, b, c);
        assertThat(f.follows.result(planId))
                .allSatisfy(
                        result -> {
                            assertThat(result.outcome()).isEqualTo("FOLLOWED");
                            assertThat(result.fromVersion()).isEqualTo(1);
                            assertThat(result.toVersion()).isEqualTo(2);
                            assertThat(result.applicationVersion()).isEqualTo(2);
                            assertThat(result.reason()).isNull();
                        });
        assertThat(f.follows.result(planId).getFirst().applicationName()).isEqualTo("应用A");
        ApiSamples.print(
                "GET /nocode/design/follow-result?planId=…（全部跟上）",
                Map.of("planId", planId),
                f.follows.result(planId));
        ApiSamples.print(
                "GET /nocode/application/object-follow?id=…（已跟上）",
                Map.of("id", a),
                f.follows.list(a, f.owner));
    }

    /** 计划阶段的两条提示文案逐字；提示不阻断，计划仍是可执行状态。 */
    @Test
    void planCarriesFollowAndExposureHintsWithoutBlocking() {
        // 应用 A：成员 R 的可查看字段是「全部」；应用 B 的上限改成清单；应用 C 保持默认。
        Mockito.when(
                        servicesContext
                                .getBean(com.lingan.ucp.module.system.api.user.AdminUserApi.class)
                                .getUser(25001L))
                .thenAnswer(
                        invocation -> {
                            var user =
                                    new com.lingan.ucp.module.system.api.user.dto
                                            .AdminUserRespDTO();
                            user.setNickname("张三");
                            return user;
                        });
        f.authorization.save(
                new ApplicationAuthorization.Save(
                        a,
                        0,
                        List.of(
                                new Member(
                                        "USER",
                                        "25001",
                                        List.of(
                                                new ObjectGrant(
                                                        jia,
                                                        Set.of("READ"),
                                                        "OWN",
                                                        ALL,
                                                        Set.of(),
                                                        ALL,
                                                        Set.of()))),
                                new Member(
                                        "USER",
                                        "25002",
                                        List.of(
                                                new ObjectGrant(
                                                        jia,
                                                        Set.of("READ"),
                                                        "OWN",
                                                        Set.of(f.fieldId(jia, "name")),
                                                        Set.of(),
                                                        Set.of(),
                                                        Set.of()))))),
                f.owner);
        f.sharing.save(
                new ObjectSharing.Save(
                        jia,
                        b,
                        1,
                        new ObjectGrant(
                                jia,
                                Set.of("READ"),
                                "ALL",
                                Set.of(f.fieldId(jia, "name")),
                                Set.of(),
                                Set.of(),
                                Set.of()),
                        "改成清单"),
                f.owner);
        f.addField(jia, "note", "备注二");
        DataCenter.PublishPlan plan = f.plan(jia);
        assertThat(plan.state()).as("提示不阻断").isEqualTo("PENDING");
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(plan.applicationUpgrades()).as("加普通字段不进暂停名单").isEmpty();
        assertThat(plan.checks())
                .filteredOn(check -> check.code().equals("APPLICATION_FOLLOW"))
                .extracting(DataCenter.Check::message)
                .containsExactly("发布后将自动跟随并生效的应用（3 个）：应用A、应用B、应用C。不需要再到应用里同步、保存、发布。");
        assertThat(plan.checks())
                .filteredOn(check -> check.code().equals("FIELD_EXPOSURE"))
                .extracting(DataCenter.Check::message)
                .containsExactly(
                        "本次新增字段：备注二。应用跟随后，这些字段会自动对“可查看字段”选了“全部”的成员可见——"
                                + "应用A：应用创建人、张三；应用B：需在数据权限里勾选后才可见；应用C：应用创建人。");
        assertThat(plan.checks())
                .noneMatch(
                        check ->
                                check.code().equals("APPLICATION_FOLLOW_PENDING")
                                        || check.code().equals("HANDLING_IN_FLIGHT"));
        ApiSamples.print(
                "对象发布计划里的两条提示（checks 里 code=APPLICATION_FOLLOW / FIELD_EXPOSURE）",
                Map.of("objectId", jia),
                plan.checks().stream()
                        .filter(
                                check ->
                                        check.code().equals("APPLICATION_FOLLOW")
                                                || check.code().equals("FIELD_EXPOSURE"))
                        .toList());
        f.publish(plan, "计划里有提示也照常执行", f.owner);
        assertFollowed(a, plan.id(), 2);
    }

    /** P11：改字段名同样是兼容改动，应用照跟；不出现新增字段的提示。 */
    @Test
    void renamingAFieldIsCompatibleAndFollows() {
        f.renameField(jia, "memo", "说明");
        DataCenter.PublishPlan plan = f.plan(jia);
        assertThat(plan.applicationUpgrades()).isEmpty();
        assertThat(plan.checks()).anyMatch(check -> check.code().equals("APPLICATION_FOLLOW"));
        assertThat(plan.checks()).noneMatch(check -> check.code().equals("FIELD_EXPOSURE"));
        f.publish(plan, "改名", f.owner);
        assertFollowed(a, plan.id(), 2);
    }

    // ── 5.2-3、5.2-4：单个应用失败不影响别人 ──

    /** 应用 B 按新版本过不了应用发布校验（它的共享授权被撤销）：对象发布成功，A、C 跟上，B 版本不变并记为待处理，原因就是那条校验文案。 */
    @Test
    void oneApplicationFailingValidationDoesNotStopTheOthers() {
        f.sharing.save(new ObjectSharing.Save(jia, b, 1, null, "撤销 B 的授权"), f.owner);
        long draftDependencies = f.dependencyRows(b, "draft");
        long publishedDependencies = f.dependencyRows(b, "published");
        String planId = f.publishNewField(jia, "note", "增加备注二");
        assertThat(f.reference(jia).versionNo()).as("对象发布成功").isEqualTo(2);
        assertFollowed(a, planId, 2);
        assertFollowed(c, planId, 2);
        assertUntouched(b, 1, 1);
        Map<String, Object> state = f.state(b, jia);
        assertThat(state)
                .containsEntry("state", "PENDING")
                .containsEntry("pending_code", "VALIDATION")
                .containsEntry("pending_version", 2)
                .containsEntry("enabled", true);
        assertThat(state.get("pending_reason").toString()).startsWith("数据对象“甲”授予应用“应用B”的共享授权已被撤销。");
        assertThat(f.logs(b, jia)).hasSize(1);
        assertThat(f.logs(b, jia).getFirst())
                .containsEntry("outcome", "PENDING")
                .containsEntry("pending_code", "VALIDATION")
                .containsEntry("plan_id", planId)
                .containsEntry("application_version_before", 1);
        assertThat(f.logs(b, jia).getFirst().get("application_version_after")).isNull();
        assertThat(f.draftReference(b, jia).path("versionNo").asInt()).as("B 的草稿也没被动").isEqualTo(1);
        assertThat(f.dependencyRows(b, "draft")).isEqualTo(draftDependencies);
        assertThat(f.dependencyRows(b, "published")).isEqualTo(publishedDependencies);
        assertThat(f.follows.result(planId))
                .extracting(ApplicationFollows.FollowResult::outcome)
                .containsExactly("FOLLOWED", "PENDING", "FOLLOWED");
        // 数据管理员重新授权后点「重试」：跟上。
        f.sharing.save(
                new ObjectSharing.Save(
                        jia,
                        b,
                        2,
                        new ObjectGrant(jia, Set.of("READ"), "ALL", ALL, Set.of(), ALL, Set.of()),
                        "重新授权"),
                f.owner);
        ApplicationFollows.FollowRun retried =
                f.follows.run(new ApplicationFollows.Run(b, jia), f.owner);
        assertThat(retried.outcome()).isEqualTo("FOLLOWED");
        assertThat(retried.follow().state()).isEqualTo("FOLLOWING");
        assertThat(retried.follow().pinnedVersion()).isEqualTo(2);
        assertThat(retried.application().application().publishedVersion()).isEqualTo(2);
        assertThat(f.logs(b, jia).getLast())
                .containsEntry("outcome", "FOLLOWED")
                .containsEntry("trigger_kind", "MANUAL");
        assertThat(f.logs(b, jia).getLast().get("plan_id")).isNull();
    }

    /** 跟随中途出了非业务异常（版本行已写、移动发布指针时出错）：对象发布仍成功；应用记为待处理（系统错误）；保存点里的半截写入一行都没留下。 */
    @Test
    void unexpectedFailureLeavesNoHalfWrittenRows() {
        f.addField(jia, "note", "备注二");
        DataCenter.PublishPlan plan = f.plan(jia);
        writeFailure.failAfter("UPDATE public.nocode_application SET published_version");
        try {
            f.publish(plan, "故障注入", f.owner);
        } finally {
            writeFailure.clear();
        }
        assertThat(f.reference(jia).versionNo()).as("对象发布成功").isEqualTo(2);
        for (String app : List.of(a, b, c)) {
            assertUntouched(app, 1, 1);
            assertThat(f.dependencyRows(app, "published")).isEqualTo(1);
            assertThat(f.draftReference(app, jia).path("versionNo").asInt()).isEqualTo(1);
            assertThat(f.state(app, jia))
                    .containsEntry("state", "PENDING")
                    .containsEntry("pending_code", "ERROR")
                    .containsEntry("pending_reason", "系统错误，请重试；详见日志")
                    .containsEntry("pending_version", 2);
            assertThat(f.logs(app, jia)).hasSize(1);
            assertThat(f.logs(app, jia).getFirst())
                    .containsEntry("outcome", "PENDING")
                    .containsEntry("pending_code", "ERROR");
        }
        // 故障消失后定时重试把它们补上。
        assertThat(f.follows.retryPending(50).followed()).isEqualTo(3);
        for (String app : List.of(a, b, c)) {
            assertThat(f.publishedVersion(app)).isEqualTo(2);
            assertThat(f.state(app, jia)).containsEntry("state", "FOLLOWING");
            assertThat(f.logs(app, jia).getLast()).containsEntry("trigger_kind", "RETRY_JOB");
        }
    }

    // ── 5.2-5、5.2-6 ──

    /** 开关关着的应用不跟、不写日志；再打开立刻跟上。 */
    @Test
    void switchedOffApplicationStaysUntilSwitchedOnAgain() {
        ApiSamples.print(
                "GET /nocode/application/object-follow?id=…（从没拨过开关：没有状态行，等于默认开）",
                Map.of("id", b),
                f.follows.list(b, f.owner));
        ApplicationFollows.FollowRun off =
                f.follows.toggle(new ApplicationFollows.Switch(b, jia, false, 0), f.owner);
        ApiSamples.print(
                "POST /nocode/application/object-follow（关）",
                new ApplicationFollows.Switch(b, jia, false, 0),
                off);
        assertThat(off.follow().enabled()).isFalse();
        assertThat(off.follow().revision()).isEqualTo(1);
        assertThat(off.outcome()).isNull();
        String planId = f.publishNewField(jia, "note", "增加备注二");
        assertFollowed(a, planId, 2);
        assertUntouched(b, 1, 1);
        assertThat(f.logs(b, jia)).as("关着时不记日志").isEmpty();
        assertThat(f.state(b, jia))
                .containsEntry("enabled", false)
                .containsEntry("state", "FOLLOWING");
        ApplicationFollows.ObjectFollow listed = f.follows.list(b, f.owner).getFirst();
        assertThat(listed.enabled()).isFalse();
        assertThat(listed.pinnedVersion()).isEqualTo(1);
        assertThat(listed.latestVersion()).isEqualTo(2);
        // 关着时「立即跟随」被拒；修订号不对被拒。
        assertThatThrownBy(() -> f.follows.run(new ApplicationFollows.Run(b, jia), f.owner))
                .hasMessage("请先打开自动跟随");
        assertThatThrownBy(
                        () ->
                                f.follows.toggle(
                                        new ApplicationFollows.Switch(b, jia, true, 0), f.owner))
                .hasMessage("自动跟随开关已被修改，请刷新后重试");
        ApiSamples.print(
                "GET /nocode/application/object-follow?id=…（关着且落后）",
                Map.of("id", b),
                f.follows.list(b, f.owner));
        ApplicationFollows.FollowRun on =
                f.follows.toggle(new ApplicationFollows.Switch(b, jia, true, 1), f.owner);
        ApiSamples.print(
                "POST /nocode/application/object-follow（开：立刻补一次跟随）",
                new ApplicationFollows.Switch(b, jia, true, 1),
                on);
        assertThat(on.outcome()).isEqualTo("FOLLOWED");
        assertThat(on.follow().enabled()).isTrue();
        assertThat(on.follow().revision()).isEqualTo(2);
        assertThat(on.draftSynced()).isTrue();
        assertThat(on.application().application().publishedVersion()).isEqualTo(2);
        assertThat(f.publishedVersion(b)).isEqualTo(2);
        assertThat(f.logs(b, jia)).hasSize(1);
        assertThat(f.logs(b, jia).getFirst()).containsEntry("trigger_kind", "SWITCH_ON");
        assertThat(f.publishReason(b)).isEqualTo("系统跟随：对象「甲」V1 → V2。");
    }

    /** 幂等：同一个已成功的计划再执行一次不产生新的应用版本；应用已在新版本时再点「立即跟随」是「已是最新」。 */
    @Test
    void repeatedExecutionAndUpToDateApplicationsAreSkipped() {
        f.addField(jia, "note", "备注二");
        DataCenter.PublishPlan plan = f.plan(jia);
        f.publish(plan, "第一次", f.owner);
        assertThat(
                        publisher
                                .execute(
                                        new DataCenter.ExecutePlan(plan.id(), "同一个计划再点一次"), f.owner)
                                .state())
                .isEqualTo("SUCCEEDED");
        for (String app : List.of(a, b, c)) {
            assertThat(f.versionCount(app)).isEqualTo(2);
            assertThat(f.logs(app, jia)).hasSize(1);
        }
        ApplicationFollows.FollowRun again =
                f.follows.run(new ApplicationFollows.Run(a, jia), f.owner);
        ApiSamples.print(
                "POST /nocode/application/object-follow/run（已是最新）",
                new ApplicationFollows.Run(a, jia),
                again);
        assertThat(again.outcome()).isEqualTo("UP_TO_DATE");
        assertThat(f.versionCount(a)).isEqualTo(2);
        assertThat(f.logs(a, jia)).hasSize(1);
    }

    // ── 5.2-7：被暂停名单里的应用不跟 ──

    /** 停用甲的一个字段并发布：三个应用进暂停名单、确认后被停用，没有任何跟随日志——与没有自动跟随时完全一样。 */
    @Test
    void incompatiblePublishStillSuspendsAndNeverFollows() {
        f.removeField(jia, "memo");
        DataCenter.PublishPlan plan = f.plan(jia);
        assertThat(plan.applicationUpgrades())
                .extracting(ObjectApplicationUpgrade.Impact::applicationId)
                .containsExactlyInAnyOrder(a, b, c);
        assertThat(plan.checks())
                .noneMatch(
                        check ->
                                check.code().equals("APPLICATION_FOLLOW")
                                        || check.code().equals("FIELD_EXPOSURE"));
        assertThatThrownBy(
                        () ->
                                publisher.execute(
                                        new DataCenter.ExecutePlan(plan.id(), "遗漏暂停确认"), f.owner))
                .hasMessageContaining("确认暂停");
        f.publish(f.plan(jia), "确认维护", f.owner);
        for (String app : List.of(a, b, c)) {
            assertUntouched(app, 1, 1);
            assertThat(f.apps.get(app).application().status()).isEqualTo("DISABLED");
            assertThat(f.logs(app, jia)).isEmpty();
            assertThat(f.state(app, jia)).isNull();
        }
    }

    /**
     * 被暂停（已停用）的应用点「立即跟随」：明确拒绝并说清怎么恢复，不答一句「已是最新」；开关照常能拨，拨开时不跟、结果里也不谎报。 联调时发现：原先返回 UP_TO_DATE
     * 而什么都没发生，界面上那一行一直停在「待跟随 / 立即跟随」。
     */
    @Test
    void suspendedApplicationRejectsRunAndNeverClaimsUpToDate() {
        f.removeField(jia, "memo");
        f.publish(f.plan(jia), "确认维护", f.owner);
        assertThat(f.apps.get(a).application().status()).isEqualTo("DISABLED");
        ApplicationFollows.ObjectFollow listed = f.follows.list(a, f.owner).getFirst();
        assertThat(listed.enabled()).isTrue();
        assertThat(listed.pinnedVersion()).isEqualTo(1);
        assertThat(listed.latestVersion()).isEqualTo(2);
        assertThatThrownBy(() -> f.follows.run(new ApplicationFollows.Run(a, jia), f.owner))
                .hasMessage("应用已停用，自动跟随不处理已停用的应用。请点这一行的“同步最新版本”，调整配置并保存，然后“发布并启用”。");
        ApplicationFollows.FollowRun off =
                f.follows.toggle(new ApplicationFollows.Switch(a, jia, false, 0), f.owner);
        assertThat(off.outcome()).isNull();
        ApplicationFollows.FollowRun on =
                f.follows.toggle(new ApplicationFollows.Switch(a, jia, true, 1), f.owner);
        assertThat(on.follow().enabled()).isTrue();
        assertThat(on.follow().pinnedVersion()).isEqualTo(1);
        assertThat(on.outcome()).as("落后且什么都没做：结果留空，不写「已是最新」").isNull();
        assertUntouched(a, 1, 1);
        assertThat(f.logs(a, jia)).isEmpty();
        assertThat(f.apps.get(a).application().status()).isEqualTo("DISABLED");
    }

    /** 加一个必填且没有默认值的字段：同样进暂停名单，不跟（P11 的对照）。 */
    @Test
    void requiredFieldWithoutDefaultIsNotACompatibleChange() {
        f.addField(jia, FollowFixture.field("must", "必填项", "TEXT", true));
        DataCenter.PublishPlan plan = f.plan(jia);
        assertThat(plan.applicationUpgrades()).hasSize(3);
        assertThat(plan.checks()).noneMatch(check -> check.code().equals("APPLICATION_FOLLOW"));
    }

    // ── 5.2-8、5.2-9：草稿 ──

    /** 发布的是「旧发布快照 + 升级」而不是草稿：草稿里没发布的新视图不会被顺带发出去；草稿里它还在，引用提到了新版本。 */
    @Test
    void followPublishesTheReleasedSnapshotNotTheDraft() {
        ApplicationCenter.Detail head = f.apps.get(a);
        f.apps.save(
                new ApplicationCenter.Save(
                        a,
                        head.application().revision(),
                        head.application().code(),
                        "应用A（草稿里改了名）",
                        null,
                        null,
                        new ApplicationCenter.Definition(
                                head.draft().objects(), List.of(f.view("unpublished", jia)))),
                f.owner);
        String planId = f.publishNewField(jia, "note", "增加备注二");
        assertFollowed(a, planId, 2);
        ApplicationCenter.Published released = f.apps.published(a);
        assertThat(released.definition().resources()).as("没发布的视图没有被发出去").isEmpty();
        assertThat(released.application().name()).as("发布快照里的名称仍是上次发布时的").isEqualTo("应用A");
        ApplicationCenter.Detail draft = f.apps.get(a);
        assertThat(draft.draft().resources())
                .extracting(ApplicationCenter.Resource::id)
                .containsExactly("unpublished");
        assertThat(draft.draft().objects().getFirst().versionNo()).isEqualTo(2);
        assertThat(draft.application().name()).isEqualTo("应用A（草稿里改了名）");
    }

    /** 草稿提版后通不过草稿校验：应用照常跟上，草稿原样不动，接口返回 draftSynced=false 与原因。 */
    @Test
    void draftThatFailsValidationIsLeftUntouched() {
        f.configure(false, "strict");
        f.publishNewField(jia, "note", "总开关关着时发布");
        f.configure(true, "strict");
        // 直接把草稿改成一份过不了校验的配置（视图引用了不存在的字段）。
        jdbc.update(
                "UPDATE public.nocode_application SET design_json = jsonb_set(design_json,"
                        + " '{resources}', CAST(? AS jsonb)) WHERE id=?",
                "[{\"id\":\"broken\",\"kind\":\"VIEW\",\"code\":\"follow_broken\",\"name\":\"坏视图\","
                        + "\"config\":{\"objectId\":\""
                        + jia
                        + "\",\"fieldIds\":[\"999999999\"],\"equal\":{},\"descending\":false,"
                        + "\"pageSize\":10}}]",
                Long.valueOf(a));
        String before =
                jdbc.queryForObject(
                        "SELECT design_json::text FROM public.nocode_application WHERE id=?",
                        String.class,
                        Long.valueOf(a));
        ApplicationFollows.FollowRun run =
                f.follows.run(new ApplicationFollows.Run(a, jia), f.owner);
        assertThat(run.outcome()).isEqualTo("FOLLOWED");
        assertThat(run.draftSynced()).isFalse();
        assertThat(run.draftReason()).isNotBlank();
        assertThat(f.publishedVersion(a)).isEqualTo(2);
        assertThat(f.publishedReference(a, jia).path("versionNo").asInt()).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT design_json::text FROM public.nocode_application WHERE"
                                        + " id=?",
                                String.class,
                                Long.valueOf(a)))
                .as("草稿原样不动")
                .isEqualTo(before);
    }

    /** 从没发布过的应用：只同步草稿，不建版本，不记待处理，不写日志。 */
    @Test
    void neverPublishedApplicationOnlyGetsItsDraftSynced() {
        String unpublished = f.draft("d", "没发布过", f.owner, List.of(), jia).application().id();
        f.publishNewField(jia, "note", "增加备注二");
        assertThat(f.versionCount(unpublished)).isZero();
        assertThat(f.draftReference(unpublished, jia).path("versionNo").asInt()).isEqualTo(2);
        assertThat(f.state(unpublished, jia)).isNull();
        assertThat(f.logs(unpublished, jia)).isEmpty();
        ApplicationFollows.ObjectFollow listed = f.follows.list(unpublished, f.owner).getFirst();
        assertThat(listed.pinnedVersion()).as("从没发布过：取草稿里固定的版本").isEqualTo(2);
        assertThat(listed.enabled()).isTrue();
        assertThat(listed.revision()).isZero();
    }

    // ── 5.2-10、5.2-11、5.2-12：在途审批 ──

    /** 过渡做法：本对象上有在途流程 ⇒ 不跟，记待处理，原因逐字；别的对象上的在途流程不拦；审批走完后定时重试跟上。 */
    @Test
    void inFlightProcessOnThisObjectDefersTheFollow() {
        String yi = f.object("yi", "乙");
        String both = f.app("both", "两个对象的应用", jia, yi);
        f.runningProcess(a, jia);
        f.runningProcess(both, yi);
        f.addField(jia, "note", "备注二");
        DataCenter.PublishPlan plan = f.plan(jia);
        assertThat(plan.checks())
                .filteredOn(check -> check.code().equals("APPLICATION_FOLLOW_PENDING"))
                .extracting(DataCenter.Check::message)
                .containsExactly(
                        "应用“应用A”暂时跟不上：还有 1 条流程审批、0"
                                + " 条办理申请没有完结。它继续按当前版本运行；这些审批完结（通过并生效，或由申请人放弃）后自动跟上。");
        assertThat(plan.checks())
                .filteredOn(check -> check.code().equals("APPLICATION_FOLLOW"))
                .extracting(DataCenter.Check::message)
                .containsExactly("发布后将自动跟随并生效的应用（3 个）：应用B、应用C、两个对象的应用。不需要再到应用里同步、保存、发布。");
        assertThat(plan.state()).isEqualTo("PENDING");
        f.publish(plan, "有在途流程", f.owner);
        assertUntouched(a, 1, 1);
        assertThat(f.state(a, jia))
                .containsEntry("state", "PENDING")
                .containsEntry("pending_code", "IN_FLIGHT")
                .containsEntry(
                        "pending_reason",
                        "应用里还有 1 条流程审批、0"
                            + " 条办理申请没有完结（审批中、审批通过后还没生效、或生效失败还没放弃的都算）。全部完结后系统会自动跟上，也可以点“重试”；生效失败的申请要由申请人放弃或重新提交。");
        assertThat(f.logs(a, jia).getFirst())
                .containsEntry("outcome", "PENDING")
                .containsEntry("pending_code", "IN_FLIGHT");
        assertFollowed(b, plan.id(), 2);
        assertThat(f.publishedVersion(both)).as("在途流程在别的对象上：照跟").isEqualTo(2);
        ApiSamples.print(
                "对象发布计划里的提示（code=APPLICATION_FOLLOW_PENDING）",
                Map.of("objectId", jia),
                plan.checks().stream()
                        .filter(check -> check.code().equals("APPLICATION_FOLLOW_PENDING"))
                        .toList());
        ApiSamples.print(
                "GET /nocode/design/follow-result?planId=…（有一个应用在途没跟上）",
                Map.of("planId", plan.id()),
                f.follows.result(plan.id()));
        ApiSamples.print(
                "GET /nocode/application/object-follow?id=…（待处理：有在途审批）",
                Map.of("id", a),
                f.follows.list(a, f.owner));

        // 仍在途时定时重试不动它，也不重复写日志。
        ApplicationFollowRetry retry = retry();
        assertThat(retry.followed()).isZero();
        assertThat(f.logs(a, jia)).hasSize(1);
        assertUntouched(a, 1, 1);
        // 取接口样例时才多点一次「重试」（它会多写一行日志，不影响下面的断言）。
        if (ApiSamples.on())
            ApiSamples.print(
                    "POST /nocode/application/object-follow/run（重试，仍在途）",
                    new ApplicationFollows.Run(a, jia),
                    f.follows.run(new ApplicationFollows.Run(a, jia), f.owner));
        // 审批走完：定时重试跟上。
        f.finishProcesses(a);
        assertThat(retry().followed()).isEqualTo(1);
        assertThat(f.publishedVersion(a)).isEqualTo(2);
        assertThat(f.state(a, jia)).containsEntry("state", "FOLLOWING");
        assertThat(f.state(a, jia).get("pending_code")).isNull();
        assertThat(f.logs(a, jia).getLast())
                .containsEntry("outcome", "FOLLOWED")
                .containsEntry("trigger_kind", "RETRY_JOB");
    }

    private record ApplicationFollowRetry(int examined, int followed) {}

    private ApplicationFollowRetry retry() {
        var result = f.follows.retryPending(50);
        return new ApplicationFollowRetry(result.examined(), result.followed());
    }

    /** 进程内兜底走的是同一个入口：没有调度中心时不用人点「重试」——审批还在途的那一轮不动它，审批走完后的下一轮自己跟上； 调度中心的执行端开着时兜底不跑。 */
    @Test
    void inProcessFallbackFollowsOnceTheApprovalIsDone() {
        f.runningProcess(a, jia);
        f.publishNewField(jia, "note", "增加备注二");
        assertThat(f.state(a, jia)).containsEntry("state", "PENDING");
        var fallback =
                new com.lingan.ucp.nocode.runtime.job.application.ApplicationFollowRetryFallback();
        org.springframework.test.util.ReflectionTestUtils.setField(fallback, "follows", f.follows);
        org.springframework.test.util.ReflectionTestUtils.setField(
                fallback,
                "redissonProvider",
                servicesContext.getBeanProvider(org.redisson.api.RedissonClient.class));
        org.springframework.test.util.ReflectionTestUtils.setField(fallback, "enabled", true);
        assertThat(fallback.tick()).as("这一轮跑了，但审批在途，不动应用").isTrue();
        assertUntouched(a, 1, 1);
        assertThat(f.logs(a, jia)).hasSize(1);

        f.finishProcesses(a);
        org.springframework.test.util.ReflectionTestUtils.setField(
                fallback, "powerJobWorker", true);
        assertThat(fallback.tick()).as("调度中心的执行端开着：兜底不跑").isFalse();
        assertUntouched(a, 1, 1);

        org.springframework.test.util.ReflectionTestUtils.setField(
                fallback, "powerJobWorker", false);
        fallback.scheduled();
        assertThat(f.publishedVersion(a)).isEqualTo(2);
        assertThat(f.state(a, jia)).containsEntry("state", "FOLLOWING");
        assertThat(f.logs(a, jia).getLast())
                .containsEntry("outcome", "FOLLOWED")
                .containsEntry("trigger_kind", "RETRY_JOB");
        assertThat(f.publishReason(a)).startsWith("系统跟随：");
    }

    /** 过渡做法：应用里有未完结的办理申请（哪怕在别的对象上）⇒ 不跟；对象发布窗口提示本对象在审批中的申请数。 */
    @Test
    void unresolvedHandlingRequestDefersTheFollowAndIsHinted() {
        String yi = f.object("yi", "乙");
        String both = f.app("both", "两个对象的应用", jia, yi);
        f.pendingHandling(both, yi);
        f.pendingHandling(a, jia);
        f.pendingHandling(a, jia);
        f.addField(jia, "note", "备注二");
        DataCenter.PublishPlan plan = f.plan(jia);
        assertThat(plan.checks())
                .filteredOn(check -> check.code().equals("HANDLING_IN_FLIGHT"))
                .extracting(DataCenter.Check::message)
                .containsExactly("本对象还有 2 条“新增 / 修改须审批”的申请在审批中。对象发布后，这些申请通过时将无法生效，需要申请人重新提交。");
        assertThat(plan.checks())
                .filteredOn(check -> check.code().equals("APPLICATION_FOLLOW_PENDING"))
                .extracting(DataCenter.Check::message)
                .containsExactlyInAnyOrder(
                        "应用“应用A”暂时跟不上：还有 0 条流程审批、2"
                                + " 条办理申请没有完结。它继续按当前版本运行；这些审批完结（通过并生效，或由申请人放弃）后自动跟上。",
                        "应用“两个对象的应用”暂时跟不上：还有 0 条流程审批、1"
                                + " 条办理申请没有完结。它继续按当前版本运行；这些审批完结（通过并生效，或由申请人放弃）后自动跟上。");
        ApiSamples.print(
                "对象发布计划里的提示（code=HANDLING_IN_FLIGHT / APPLICATION_FOLLOW_PENDING）",
                Map.of("objectId", jia),
                plan.checks().stream()
                        .filter(
                                check ->
                                        check.code().equals("HANDLING_IN_FLIGHT")
                                                || check.code()
                                                        .equals("APPLICATION_FOLLOW_PENDING"))
                        .toList());
        f.publish(plan, "有在途办理申请", f.owner);
        assertUntouched(a, 1, 1);
        assertUntouched(both, 1, 1);
        assertThat(f.state(both, jia))
                .containsEntry("pending_code", "IN_FLIGHT")
                .containsEntry(
                        "pending_reason",
                        "应用里还有 0 条流程审批、1"
                            + " 条办理申请没有完结（审批中、审批通过后还没生效、或生效失败还没放弃的都算）。全部完结后系统会自动跟上，也可以点“重试”；生效失败的申请要由申请人放弃或重新提交。");
        assertFollowed(b, plan.id(), 2);
        f.finishHandlings(a);
        f.finishHandlings(both);
        assertThat(f.follows.retryPending(50).followed()).isEqualTo(2);
        assertThat(f.publishedVersion(a)).isEqualTo(2);
        assertThat(f.publishedVersion(both)).isEqualTo(2);
    }

    /** 放宽后的做法（配置切到 relaxed）：预检被跳过；对方的保护没放宽时，跟随被应用发布校验拒绝，记为「校验未通过」。 */
    @Test
    void relaxedPolicySkipsThePrecheckAndReportsTheGuardAsValidationFailure() {
        f.configure(true, "relaxed");
        f.runningProcess(a, jia);
        f.addField(jia, "note", "备注二");
        DataCenter.PublishPlan plan = f.plan(jia);
        assertThat(plan.checks())
                .as("放宽后不做在途预检")
                .noneMatch(check -> check.code().equals("APPLICATION_FOLLOW_PENDING"));
        f.publish(plan, "放宽策略", f.owner);
        assertUntouched(a, 1, 1);
        assertThat(f.state(a, jia))
                .containsEntry("state", "PENDING")
                .containsEntry("pending_code", "VALIDATION")
                .containsEntry("pending_reason", "应用还有运行中的流程，不能移除或切换其业务对象版本");
        assertFollowed(b, plan.id(), 2);
    }

    // ── 5.2-13：人工发布时自动提版 ──

    /** 开着跟随、应用落后：设计者自己点发布时系统先提版再校验，发布出去的是新版本，草稿随后同步，记一行 APPLICATION_PUBLISH 日志。 */
    @Test
    void manualPublishLiftsBehindReferencesWhenFollowing() {
        f.configure(false, "strict");
        f.publishNewField(jia, "note", "总开关关着时发布");
        f.configure(true, "strict");
        assertUntouched(a, 1, 1);
        ApplicationCenter.Detail head = f.apps.get(a);
        ApplicationCenter.Detail released =
                f.apps.publish(
                        new ApplicationCenter.Revision(a, head.application().revision(), "人工发布"),
                        f.owner);
        assertThat(released.application().publishedVersion()).isEqualTo(2);
        assertThat(f.publishedReference(a, jia).path("versionNo").asInt()).isEqualTo(2);
        assertThat(f.draftReference(a, jia).path("versionNo").asInt()).as("草稿随后同步").isEqualTo(2);
        assertThat(released.draft().objects().getFirst().versionNo()).isEqualTo(2);
        assertThat(f.publishReason(a)).isEqualTo("人工发布");
        assertThat(f.logs(a, jia)).hasSize(1);
        assertThat(f.logs(a, jia).getFirst())
                .containsEntry("outcome", "FOLLOWED")
                .containsEntry("trigger_kind", "APPLICATION_PUBLISH")
                .containsEntry("from_version", 1)
                .containsEntry("to_version", 2)
                .containsEntry("application_version_after", 2);
        assertThat(f.state(a, jia)).containsEntry("followed_version", 2);
    }

    /** 对照组：关着跟随的应用人工发布不提版。 */
    @Test
    void manualPublishDoesNotLiftWhenSwitchedOff() {
        f.follows.toggle(new ApplicationFollows.Switch(a, jia, false, 0), f.owner);
        f.publishNewField(jia, "note", "增加备注二");
        ApplicationCenter.Detail head = f.apps.get(a);
        f.apps.publish(
                new ApplicationCenter.Revision(a, head.application().revision(), "人工发布"), f.owner);
        assertThat(f.publishedVersion(a)).isEqualTo(2);
        assertThat(f.publishedReference(a, jia).path("versionNo").asInt())
                .as("仍固定在旧对象版本")
                .isEqualTo(1);
        assertThat(f.draftReference(a, jia).path("versionNo").asInt()).isEqualTo(1);
        assertThat(f.logs(a, jia)).isEmpty();
    }

    /** 提版后校验不过：照常报错，文案前面带上「按新版本校验未通过」的前缀；没提版时原文案不变。 */
    @Test
    void liftedPublishFailureCarriesThePrefix() {
        f.configure(false, "strict");
        f.publishNewField(jia, "note", "总开关关着时发布");
        f.sharing.save(new ObjectSharing.Save(jia, a, 1, null, "撤销"), f.owner);
        ApplicationCenter.Detail head = f.apps.get(a);
        ApplicationCenter.Revision revision =
                new ApplicationCenter.Revision(a, head.application().revision(), "人工发布");
        assertThatThrownBy(() -> f.apps.publish(revision, f.owner))
                .as("总开关关着：不提版，原文案")
                .hasMessageStartingWith("数据对象“甲”授予应用“应用A”的共享授权已被撤销。");
        f.configure(true, "strict");
        assertThatThrownBy(() -> f.apps.publish(revision, f.owner))
                .hasMessageStartingWith(
                        "对象「甲」已有新版本 V2，本应用开着自动跟随，按新版本校验未通过：数据对象“甲”授予应用“应用A”的共享授权已被撤销。");
        assertUntouched(a, 1, 1);
    }

    // ── 5.2-14：总开关 ──

    /** nocode.follow.enabled=false：对象发布不做任何跟随，计划里没有任何跟随提示，人工发布不提版——与没有这个功能时一致。 */
    @Test
    void globalSwitchOffRestoresThePreviousBehaviour() {
        f.configure(false, "strict");
        f.addField(jia, "note", "备注二");
        DataCenter.PublishPlan plan = f.plan(jia);
        assertThat(plan.checks())
                .noneMatch(
                        check ->
                                Set.of(
                                                "APPLICATION_FOLLOW",
                                                "APPLICATION_FOLLOW_PENDING",
                                                "FIELD_EXPOSURE",
                                                "HANDLING_IN_FLIGHT")
                                        .contains(check.code()));
        f.publish(plan, "总开关关着", f.owner);
        for (String app : List.of(a, b, c)) {
            assertUntouched(app, 1, 1);
            assertThat(f.state(app, jia)).isNull();
            assertThat(f.logs(app, jia)).isEmpty();
            assertThat(f.draftReference(app, jia).path("versionNo").asInt()).isEqualTo(1);
        }
        assertThat(f.follows.result(plan.id())).isEmpty();
        ApplicationCenter.Detail head = f.apps.get(a);
        f.apps.publish(
                new ApplicationCenter.Revision(a, head.application().revision(), "人工发布"), f.owner);
        assertThat(f.publishedReference(a, jia).path("versionNo").asInt()).isEqualTo(1);
        assertThat(f.follows.retryPending(50).examined()).isZero();
        assertThatThrownBy(() -> f.follows.run(new ApplicationFollows.Run(a, jia), f.owner))
                .hasMessage("自动跟随已被系统管理员停用");
    }

    // ── 5.2-15：身份 ──

    /** 点对象发布的人不是应用设计者：照样跟上，版本的创建人是他；拨开关、点「立即跟随」的人不是设计者则被拒。 */
    @Test
    void objectPublisherNeedNotBeTheApplicationDesigner() {
        String others = f.app("o", "别人的应用", 25010L, List.of(), jia);
        String planId = f.publishNewField(jia, "note", "增加备注二");
        assertFollowed(others, planId, 2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT creator FROM public.nocode_application_version WHERE"
                                        + " application_id=? AND version_no=2",
                                String.class,
                                Long.valueOf(others)))
                .isEqualTo("10001");
        assertThatThrownBy(
                        () ->
                                f.follows.toggle(
                                        new ApplicationFollows.Switch(others, jia, false, 0),
                                        f.owner))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> f.follows.run(new ApplicationFollows.Run(others, jia), f.owner))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> f.follows.list(others, f.owner))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(f.follows.list(others, 25010L)).hasSize(1);
    }

    /** 没引用这个对象的应用不受影响；开关只能拨给应用引用了的对象。 */
    @Test
    void applicationsNotReferencingTheObjectAreIgnored() {
        String yi = f.object("yi", "乙");
        String other = f.app("x", "只引用乙", yi);
        f.publishNewField(jia, "note", "增加备注二");
        assertThat(f.publishedVersion(other)).isEqualTo(1);
        assertThat(f.state(other, jia)).isNull();
        assertThatThrownBy(
                        () ->
                                f.follows.toggle(
                                        new ApplicationFollows.Switch(other, jia, false, 0),
                                        f.owner))
                .hasMessage("应用没有引用这个对象");
    }
}
