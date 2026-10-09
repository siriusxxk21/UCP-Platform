package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.ApplicationAuthorization;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 订阅鉴权所依赖的前提：WebSocket 处理线程上没有登录上下文、没有请求、没有任务入口上下文，在这样的线程上调用
 * ApplicationRuntimePolicy.effectiveGrants 必须与请求线程上的结果完全相同。真实授权配置与真实数据库。
 */
class RecordLiveSubscriptionPolicyIntegrationTest {
    private static final long STRANGER = 30003L;

    private RecordLiveFixture live;

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
        live = new RecordLiveFixture();
    }

    @AfterEach
    void cleanup() {
        live.cleanup();
    }

    @Test
    void effectiveGrantsOnABareThreadMatchTheCallingThread() throws Exception {
        DataCenter.Definition all = live.plain(false);
        DataCenter.Definition own = live.plain(false);
        DataCenter.Definition none = live.plain(false);
        String app = live.app(List.of(), all, own, none);
        live.authorize(
                app,
                grant(all, "ALL", Set.of("READ", "UPDATE")),
                grant(own, "OWN", Set.of("READ", "CREATE")));
        ApplicationRuntimePolicy policy = servicesContext.getBean(ApplicationRuntimePolicy.class);

        for (DataCenter.Definition d : List.of(all, own, none))
            for (long actor :
                    List.of(RecordLiveFixture.OWNER, RecordLiveFixture.MEMBER, STRANGER)) {
                List<ApplicationAuthorization.ObjectGrant> here =
                        policy.scopeGrants(app, d.objectId(), actor);
                List<ApplicationAuthorization.ObjectGrant> bare;
                try (var executor = Executors.newSingleThreadExecutor()) {
                    bare =
                            executor.submit(() -> policy.scopeGrants(app, d.objectId(), actor))
                                    .get(30, TimeUnit.SECONDS);
                }
                assertThat(bare)
                        .as("对象 %s 操作者 %s：裸线程上的授权与调用线程一致", d.objectId(), actor)
                        .isEqualTo(here);
            }

        // 结果本身符合预期，排除「两边都为空所以相等」的空转。
        assertThat(policy.scopeGrants(app, all.objectId(), RecordLiveFixture.OWNER))
                .as("应用创建人取得对象授予应用的上限")
                .hasSize(1)
                .allMatch(g -> g.actions().contains("READ") && "ALL".equals(g.scope()));
        assertThat(policy.scopeGrants(app, all.objectId(), RecordLiveFixture.MEMBER))
                .hasSize(1)
                .allMatch(
                        g ->
                                g.actions().contains("READ")
                                        && "ALL".equals(g.scope())
                                        && g.actionScopes().isEmpty());
        assertThat(policy.scopeGrants(app, own.objectId(), RecordLiveFixture.MEMBER))
                .hasSize(1)
                .allMatch(g -> g.actions().contains("READ") && "OWN".equals(g.scope()));
        assertThat(policy.scopeGrants(app, none.objectId(), RecordLiveFixture.MEMBER))
                .as("成员在这个对象上没有授权")
                .isEmpty();
        assertThat(policy.scopeGrants(app, all.objectId(), STRANGER)).as("不是应用成员").isEmpty();
    }

    private ApplicationAuthorization.ObjectGrant grant(
            DataCenter.Definition d, String scope, Set<String> actions) {
        Set<String> fields = Set.of(live.field(d, "name"));
        return new ApplicationAuthorization.ObjectGrant(
                d.objectId(), actions, scope, fields, fields, Set.of(), Set.of());
    }
}
