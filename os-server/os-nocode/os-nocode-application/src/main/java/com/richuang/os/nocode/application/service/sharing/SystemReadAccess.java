package com.richuang.os.nocode.application.service.sharing;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.application.dal.dataobject.NocodeApplicationDO;
import com.richuang.os.nocode.application.dal.mapper.ApplicationMapper;
import com.richuang.os.nocode.enums.ApplicationActionEnum;
import com.richuang.os.nocode.enums.ApplicationScopeEnum;
import com.richuang.os.nocode.enums.SystemReadDenialEnum;
import com.richuang.os.nocode.metadata.service.object.DraftValidator;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 系统替配置取数的唯一前提检查：计算（查找取值、累计、统计）、数据联动、引用筛选、挑取值都从这里过。
 *
 * <p>规则：应用对来源对象的有效上限（{@link ObjectSharingService#ceiling}）满足「记录范围 = 全部记录、含查看、查看没有记录条件」，
 * 并且用到的来源字段都在上限展开后的可查看字段里 ⇒ 可以取数；否则拦，并说清是哪个对象、哪些字段、去哪改。 不再让人勾「计算取数字段」， {@code computeFields}
 * 不读不写。系统算的值按全部数据算，谁能看到结果由结果字段的权限决定。
 */
@Component
public class SystemReadAccess {
    @Resource private ObjectSharingService sharing;
    @Resource private ObjectGrantValidator grants;
    @Resource private ApplicationMapper applications;
    @Resource private DraftValidator validator;
    @Resource private ObjectMapper json;

    /** code 取 {@link SystemReadDenialEnum} 的编码；reason、action 可直接给人看。 */
    public record Denial(String code, String reason, String action) {}

    /** 通过时 denial 为 null，grant 是一份「全部记录、仅查看、清单已展开」的只读授权；不通过时 grant 为 null。 */
    public record Outcome(Denial denial, ObjectGrant grant) {}

    /** 通过返回 null。运行期口径：对象是否隐式可读按应用的已发布版本判断。 */
    public Denial check(String app, DataCenter.Definition source, Set<String> requiredFieldIds) {
        return inspect(app, source, requiredFieldIds).denial();
    }

    /** 不通过抛出带定位的业务错误；通过返回只读授权。 */
    public ObjectGrant require(
            String app, DataCenter.Definition source, Set<String> requiredFieldIds, String usedBy) {
        Outcome outcome = inspect(app, source, requiredFieldIds);
        if (outcome.denial() == null) return outcome.grant();
        throw invalid(message(app, source, outcome.denial(), usedBy));
    }

    public Outcome inspect(String app, DataCenter.Definition source, Set<String> requiredFieldIds) {
        return inspect(app, source, requiredFieldIds, sharing.ceiling(app, source));
    }

    /** 设计预览口径：对象是否隐式可读由调用方按草稿引用给出。 */
    public Outcome inspect(
            String app,
            DataCenter.Definition source,
            Set<String> requiredFieldIds,
            BooleanSupplier implied) {
        return inspect(app, source, requiredFieldIds, sharing.ceiling(app, source, implied));
    }

    private Outcome inspect(
            String app,
            DataCenter.Definition source,
            Set<String> requiredFieldIds,
            ObjectGrant ceiling) {
        String object = "「" + source.objectName() + "」";
        String application = "应用「" + applicationName(app) + "」";
        if (ceiling == null || !ceiling.actions().contains(ApplicationActionEnum.READ.getCode()))
            return denied(
                    SystemReadDenialEnum.NOT_GRANTED,
                    object + "还没有授权给" + application + "（或授权已撤销）",
                    "新增应用授权并保存");
        if (!ApplicationScopeEnum.ALL.matches(ceiling.scope()))
            return denied(
                    SystemReadDenialEnum.SCOPE,
                    object + "授给" + application + "的记录范围是“当前操作者创建的记录”，系统计算必须能读全部记录",
                    "记录范围选“全部记录”");
        if (ceiling.actionScopes().containsKey(ApplicationActionEnum.READ.getCode()))
            return denied(
                    SystemReadDenialEnum.CONDITION,
                    object + "授给" + application + "的“查看”设了记录条件，系统计算不能带条件",
                    "在“高级设置”里取消“查看的记录条件”");
        ObjectGrant resolved = grants.resolve(ceiling, source);
        if (requiredFieldIds != null && !resolved.readFields().containsAll(requiredFieldIds)) {
            List<String> missing = new ArrayList<>();
            for (FieldDefinition field : source.fields())
                if (requiredFieldIds.contains(field.id())
                        && !resolved.readFields().contains(field.id())) missing.add(field.name());
            // 所需字段在这个对象版本里已不存在或已停用：同样读不到，仍要点名。
            for (String id : requiredFieldIds)
                if (!resolved.readFields().contains(id)
                        && source.fields().stream().noneMatch(field -> field.id().equals(id)))
                    missing.add("已停用或不存在的字段");
            return denied(
                    SystemReadDenialEnum.FIELD,
                    object + "没有把字段「" + String.join("、", missing) + "」授给" + application,
                    "“可查看字段”选“全部”，或加上这些字段");
        }
        return new Outcome(
                null,
                new ObjectGrant(
                        source.objectId(),
                        Set.of(ApplicationActionEnum.READ.getCode()),
                        ApplicationScopeEnum.ALL.getCode(),
                        resolved.readFields(),
                        Set.of(),
                        resolved.readDetails(),
                        Set.of(),
                        resolved.readRelations(),
                        Set.of(),
                        Map.of(),
                        Set.of()));
    }

    private Outcome denied(SystemReadDenialEnum code, String reason, String action) {
        return new Outcome(new Denial(code.getCode(), reason, action), null);
    }

    /**
     * 三行：谁要取数、为什么取不了；去哪改；没有权限时找谁。
     *
     * <p>「去哪改」分两种：来源对象在应用的“已引用对象”里 ⇒ 指到那一行的“配置数据权限”；来源对象没有被引用（因关联而可读取的对象）⇒
     * 它不在那张表里，指到数据中心里这个对象的“应用共享授权”。
     */
    public String message(String app, DataCenter.Definition source, Denial denial, String usedBy) {
        String object = "「" + source.objectName() + "」";
        String where =
                referenced(app, source.objectId())
                        ? "应用中心 → "
                                + applicationName(app)
                                + " → 已引用对象 →"
                                + object
                                + "这一行 → 配置数据权限 → "
                                + denial.action()
                        : object
                                + "没有被本应用引用，是因关联而可读取的对象，不在“已引用对象”里。请到 数据中心 → 数据对象 →"
                                + object
                                + "→ 应用共享授权 → 应用「"
                                + applicationName(app)
                                + "」→ "
                                + denial.action();
        return usedBy
                + "要从"
                + object
                + "取数，但现在取不了："
                + denial.reason()
                + "。\n去哪改："
                + where
                + "。\n没有数据权限管理权限时，请联系数据管理员处理。";
    }

    /** 来源对象在不在应用草稿的“已引用对象”里（工作台那张表显示的就是草稿）。判断不了时按「在」处理，沿用原文案。 */
    private boolean referenced(String app, String objectId) {
        if (app == null) return true;
        NocodeApplicationDO application = applications.selectById(validator.id(app, "应用"));
        if (application == null || application.getDesignJson() == null) return true;
        try {
            for (JsonNode reference : json.readTree(application.getDesignJson()).path("objects"))
                if (objectId.equals(reference.path("objectId").asText())) return true;
            return false;
        } catch (com.fasterxml.jackson.core.JsonProcessingException unreadable) {
            return true;
        }
    }

    private String applicationName(String app) {
        if (app == null) return "当前应用";
        NocodeApplicationDO application = applications.selectById(validator.id(app, "应用"));
        return application == null ? "当前应用" : application.getAppName();
    }
}
