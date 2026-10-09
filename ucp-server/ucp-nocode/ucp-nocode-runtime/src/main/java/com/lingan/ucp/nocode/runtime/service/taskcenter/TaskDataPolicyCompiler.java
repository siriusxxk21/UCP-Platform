package com.lingan.ucp.nocode.runtime.service.taskcenter;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.DataObjectApi;
import com.lingan.ucp.nocode.api.DataScope;
import com.lingan.ucp.nocode.api.TaskCenter.Binding;
import com.lingan.ucp.nocode.api.TaskCenter.BusinessRef;
import com.lingan.ucp.nocode.api.TaskEntries;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.published.ApplicationPublishedService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.application.service.task.TaskEntryPolicyService;
import com.lingan.ucp.nocode.enums.ApplicationActionEnum;
import com.lingan.ucp.nocode.enums.ApplicationResourceKindEnum;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 根任务数据授权的编译与实时收紧；不写应用成员，不借用授予人的身份执行业务操作。 */
@Component
public class TaskDataPolicyCompiler {
    @Resource private ApplicationService applications;
    @Resource private ApplicationPublishedService published;
    @Resource private ObjectSharingService sharing;
    @Resource private DataObjectApi objects;
    @Resource private ObjectGrantValidator validator;
    @Resource private TaskEntryPolicyService entryPolicies;
    @Resource private AdminUserApi users;
    @Resource private ObjectMapper json;
    @Resource private TaskBoundViews boundViews;

    private static final Set<String> CRUD =
            Set.of(
                    ApplicationActionEnum.READ.getCode(),
                    ApplicationActionEnum.CREATE.getCode(),
                    ApplicationActionEnum.UPDATE.getCode(),
                    ApplicationActionEnum.DELETE.getCode());

    /** 仅服务端写入根任务／模板发布快照；客户端创建命令不得接受此结构。 */
    public record Frozen(long grantorId, List<ResourceGrant> resources) {
        public Frozen {
            resources = List.copyOf(resources);
        }
    }

    /** 资源固定版本及最大可委托能力；同一入口只允许其明确绑定的主对象。 */
    public record ResourceGrant(
            String entryKey, Binding binding, BusinessRef ref, List<ObjectGrant> grants) {
        public ResourceGrant {
            grants = List.copyOf(grants);
        }
    }

    /** 配置时校验授权资格并封存当时上限；不接受普通任务创建权限充当业务授权资格。 */
    public ResourceGrant compile(String entryKey, Binding binding, BusinessRef ref, long grantor) {
        requireBinding(binding, ref);
        if (entryKey == null || !entryKey.matches("[A-Za-z0-9_-]{1,80}"))
            throw invalid("任务数据入口标识无效");
        requireGrantor(binding.applicationId(), grantor);
        ApplicationCenter.Resource form = published.resolve(ref.resource());
        requireForm(form, ref.object().objectId());
        ApplicationCenter.Published release =
                published.getVersion(binding.applicationId(), ref.resource().applicationVersion());
        List<ObjectGrant> grants = limits(binding, ref, release);
        return new ResourceGrant(entryKey, binding, ref, grants);
    }

    /** 每次业务请求重查授予者及最新共享／入口上限；后来扩大上限不会自动扩大已封存任务。 */
    public List<ObjectGrant> revalidate(ResourceGrant frozen, long grantor) {
        if (frozen == null) throw invalid("任务缺少已批准的数据授权");
        requireBinding(frozen.binding(), frozen.ref());
        requireGrantor(frozen.binding().applicationId(), grantor);
        requireForm(published.resolve(frozen.ref().resource()), frozen.ref().object().objectId());
        ApplicationCenter.Published current =
                published.getCurrent(frozen.binding().applicationId());
        requireForm(
                resource(current, frozen.binding().formId(), ApplicationResourceKindEnum.FORM),
                frozen.ref().object().objectId());
        List<ObjectGrant> live = limits(frozen.binding(), frozen.ref(), current);
        List<ObjectGrant> result =
                frozen.grants().stream()
                        .filter(g -> frozen.ref().object().objectId().equals(g.objectId()))
                        .flatMap(old -> live.stream().map(now -> validator.intersect(old, now)))
                        .filter(g -> g.actions().contains(ApplicationActionEnum.READ.getCode()))
                        .map(this::crudOnly)
                        .toList();
        if (result.isEmpty()) throw invalid("该任务数据授权已收回，请联系总任务负责人");
        return result;
    }

    private void requireGrantor(String applicationId, long actor) {
        AdminUserRespDTO user = actor > 0 ? users.getUser(actor) : null;
        if (user == null || !Integer.valueOf(0).equals(user.getStatus()))
            throw invalid("任务数据授权人员已停用，请由应用负责人重新确认授权");
        applications.requireDesigner(applicationId, actor);
    }

    /**
     * 任务办理自动带入固定应用版本内的表单／列表对象能力，不要求员工成为应用成员。 当前入口仍由其显式授权覆盖；此处只给业务操作，记录范围由 TaskGroupRuntime 逐对象设置。
     */
    public Map<String, List<ObjectGrant>> applicationGrants(ResourceGrant selected, long grantor) {
        requireBinding(selected.binding(), selected.ref());
        String app = selected.binding().applicationId();
        requireGrantor(app, grantor);
        ApplicationCenter.Published frozen =
                published.getVersion(app, selected.ref().resource().applicationVersion());
        ApplicationCenter.Published current = published.getCurrent(app);
        Set<String> available = businessObjects(current);
        available.retainAll(businessObjects(frozen));
        Map<String, List<ObjectGrant>> result = new LinkedHashMap<>();
        for (ApplicationCenter.ObjectReference ref : frozen.definition().objects()) {
            if (!available.contains(ref.objectId())) continue;
            ObjectGrant stored = sharing.storedPermission(ref.objectId(), app);
            if (stored == null) continue;
            DataCenter.Definition definition =
                    objects.getVersion(ref.objectId(), ref.versionNo()).definition();
            ObjectGrant grant = crudOnly(validator.resolve(stored, definition));
            if (grant.actions().contains(ApplicationActionEnum.READ.getCode()))
                result.put(ref.objectId(), List.of(grant));
        }
        return result;
    }

    private Set<String> businessObjects(ApplicationCenter.Published release) {
        return release.definition().resources().stream()
                .filter(
                        r ->
                                ApplicationResourceKindEnum.FORM.matches(r.kind())
                                        || ApplicationResourceKindEnum.VIEW.matches(r.kind()))
                .map(r -> r.config().get("objectId"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toSet());
    }

    private void requireBinding(Binding binding, BusinessRef ref) {
        if (binding == null
                || ref == null
                || ref.resource() == null
                || ref.object() == null
                || !Objects.equals(binding.applicationId(), ref.resource().applicationId())
                || !Objects.equals(binding.formId(), ref.resource().resourceId())
                || !ApplicationResourceKindEnum.FORM.matches(ref.resource().resourceKind()))
            throw invalid("任务数据授权必须绑定明确的已发布业务资源");
    }

    private void requireForm(ApplicationCenter.Resource form, String objectId) {
        if (form == null
                || !ApplicationResourceKindEnum.FORM.matches(form.kind())
                || !Objects.equals(objectId, form.config().get("objectId")))
            throw invalid("任务业务表单与授权对象不匹配或已撤下");
    }

    private ApplicationCenter.Resource resource(
            ApplicationCenter.Published release, String id, ApplicationResourceKindEnum kind) {
        return release.definition().resources().stream()
                .filter(r -> Objects.equals(r.id(), id) && kind.matches(r.kind()))
                .findFirst()
                .orElseThrow(() -> invalid("任务引用的业务资源已撤下，请重新配置"));
    }

    private List<ObjectGrant> limits(
            Binding binding, BusinessRef ref, ApplicationCenter.Published release) {
        String objectId = ref.object().objectId();
        ObjectGrant stored = sharing.storedPermission(objectId, binding.applicationId());
        // 任务封存明确字段，不能把「全部」留作未来自动扩权；只展开到绑定的对象版本。
        DataCenter.Definition definition =
                objects.getVersion(objectId, ref.object().versionNo()).definition();
        ObjectGrant ceiling = stored == null ? null : validator.resolve(stored, definition);
        if (ceiling == null || !ceiling.actions().contains(ApplicationActionEnum.READ.getCode()))
            throw invalid("此业务对象尚未共享给应用或共享已撤销");
        com.lingan.ucp.nocode.api.ApplicationUi.View view =
                boundViews.view(binding, release, objectId);
        DataScope viewScope = boundViews.scope(view, definition);
        if (viewScope != null) ceiling = withScope(ceiling, viewScope);
        if (view != null) ceiling = withViewFields(ceiling, binding, release);
        if (binding.entryId() == null) return List.of(crudOnly(ceiling));
        ObjectGrant boundedCeiling = ceiling;
        TaskEntries.Policy policy = entryPolicies.get(binding.applicationId(), binding.entryId());
        if (policy == null || !policy.enabled()) throw invalid("任务引用的办理入口已停用");
        ApplicationCenter.Resource entry =
                resource(release, binding.entryId(), ApplicationResourceKindEnum.TASK_ENTRY);
        TaskEntries.Config config = json.convertValue(entry.config(), TaskEntries.Config.class);
        if (!Objects.equals(config.objectId(), objectId)
                || !Objects.equals(config.formId(), binding.formId()))
            throw invalid("办理入口已更换业务对象或表单，请重新确认任务授权");
        List<ObjectGrant> allowed =
                config.limits().stream()
                        .filter(g -> objectId.equals(g.objectId()))
                        .map(
                                g ->
                                        crudOnly(
                                                validator.intersect(
                                                        validator.resolve(g, definition),
                                                        boundedCeiling)))
                        .filter(g -> g.actions().contains(ApplicationActionEnum.READ.getCode()))
                        .toList();
        if (allowed.isEmpty()) throw invalid("办理入口未允许此业务对象，不能授予任务");
        return allowed;
    }

    private ObjectGrant withScope(ObjectGrant grant, DataScope limit) {
        Map<String, DataScope> scopes = new LinkedHashMap<>();
        grant.actions()
                .forEach(
                        action ->
                                scopes.put(
                                        action,
                                        DataScope.and(grant.actionScopes().get(action), limit)));
        return new ObjectGrant(
                grant.objectId(),
                grant.actions(),
                grant.scope(),
                grant.readFields(),
                grant.writeFields(),
                grant.readDetails(),
                grant.writeDetails(),
                grant.readRelations(),
                grant.writeRelations(),
                scopes,
                grant.computeFields());
    }

    /** 在授权查询前收窄字段，避免隐藏字段经搜索、候选或填充接口泄露。 */
    private ObjectGrant withViewFields(
            ObjectGrant grant, Binding binding, ApplicationCenter.Published release) {
        return new ObjectGrant(
                grant.objectId(),
                grant.actions(),
                grant.scope(),
                intersect(
                        grant.readFields(),
                        boundViews.readable(binding, release, grant.objectId())),
                intersect(grant.writeFields(), boundViews.writable(binding, release)),
                intersect(grant.readDetails(), boundViews.details(binding, release, false)),
                intersect(grant.writeDetails(), boundViews.details(binding, release, true)),
                intersect(grant.readRelations(), boundViews.relations(binding, release, false)),
                intersect(grant.writeRelations(), boundViews.relations(binding, release, true)),
                grant.actionScopes(),
                Set.of());
    }

    private Set<String> intersect(Set<String> source, Set<String> allowed) {
        return source.stream().filter(allowed::contains).collect(Collectors.toUnmodifiableSet());
    }

    private ObjectGrant crudOnly(ObjectGrant grant) {
        Set<String> actions =
                grant.actions().stream()
                        .filter(CRUD::contains)
                        .collect(Collectors.toUnmodifiableSet());
        Map<String, DataScope> scopes = new LinkedHashMap<>();
        grant.actionScopes()
                .forEach(
                        (action, scope) -> {
                            if (actions.contains(action)) scopes.put(action, scope);
                        });
        return new ObjectGrant(
                grant.objectId(),
                actions,
                grant.scope(),
                Set.copyOf(grant.readFields()),
                Set.copyOf(grant.writeFields()),
                Set.copyOf(grant.readDetails()),
                Set.copyOf(grant.writeDetails()),
                Set.copyOf(grant.readRelations()),
                Set.copyOf(grant.writeRelations()),
                scopes,
                Set.of());
    }
}
