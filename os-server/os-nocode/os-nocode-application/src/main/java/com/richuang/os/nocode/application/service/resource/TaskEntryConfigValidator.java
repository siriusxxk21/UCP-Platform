package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.sharing.ObjectGrantValidator;
import com.richuang.os.nocode.enums.*;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 入口只引用同版本的列表/表单；校验依赖和权限上限，不复制资源配置。 */
@Component
public class TaskEntryConfigValidator {
    @Resource private ObjectGrantValidator grants;
    @Resource private ObjectMapper json;

    public TaskEntries.Config validate(
            TaskEntries.Config config,
            Map<String, DataCenter.Definition> definitions,
            Map<String, ApplicationCenter.Resource> resources) {
        if (config == null || !definitions.containsKey(config.objectId()))
            throw invalid("任务入口请选择已引用对象");
        var mode = TaskEntryModeEnum.fromCode(config.mode());
        if (config.category() == null
                || config.category().isBlank()
                || config.category().length() > 40) throw invalid("任务类别应为 1 至 40 个字符");
        if (config.description() != null && config.description().length() > 240)
            throw invalid("入口说明最多 240 字");
        if (config.icon() != null && config.icon().length() > 80) throw invalid("入口图标无效");
        if (config.sortOrder() < 0 || config.sortOrder() > 10000)
            throw invalid("入口排序范围为 0 至 10000");
        if (mode == TaskEntryModeEnum.LIST) {
            var view =
                    decode(
                            resources,
                            config.viewId(),
                            ApplicationResourceKindEnum.VIEW,
                            ApplicationUi.View.class);
            if (!config.objectId().equals(view.objectId())) throw invalid("入口列表与主对象不匹配");
            if (view.detailPageId() != null) throw invalid("任务入口首期请使用记录表单详情，不绑定组合详情页");
        } else if (config.viewId() != null) throw invalid("直接填写入口不能隐式开放历史列表");
        Set<String> relatedWrites = new HashSet<>();
        if (config.formId() != null) {
            var form =
                    decode(
                            resources,
                            config.formId(),
                            ApplicationResourceKindEnum.FORM,
                            ApplicationUi.Form.class);
            if (!config.objectId().equals(form.objectId())) throw invalid("入口表单与主对象不匹配");
            for (var binding : Optional.ofNullable(form.relatedForms()).orElse(List.of())) {
                var targetForm =
                        decode(
                                resources,
                                binding.formId(),
                                ApplicationResourceKindEnum.FORM,
                                ApplicationUi.Form.class);
                relatedWrites.add(targetForm.objectId());
            }
        }
        if (config.limits() == null || config.limits().isEmpty() || config.limits().size() > 30)
            throw invalid("请配置入口对象及必要的引用读取范围");
        Set<String> ids = new HashSet<>();
        List<ApplicationAuthorization.ObjectGrant> limits = new ArrayList<>();
        ApplicationAuthorization.ObjectGrant main = null;
        for (var grant : config.limits()) {
            if (grant == null
                    || !ids.add(grant.objectId())
                    || !definitions.containsKey(grant.objectId())) throw invalid("入口授权对象不存在或重复");
            DataCenter.Definition limited = definitions.get(grant.objectId());
            limits.add(grants.normalize(grant, limited, ObjectGrantValidator.Universe.of(limited)));
            if (!grant.computeFields().isEmpty()) throw invalid("入口不能自行声明计算取数特权");
            if (config.objectId().equals(grant.objectId())) {
                main = grant;
                if (!Set.of(
                                ApplicationActionEnum.READ.getCode(),
                                ApplicationActionEnum.CREATE.getCode(),
                                ApplicationActionEnum.UPDATE.getCode(),
                                ApplicationActionEnum.DELETE.getCode())
                        .containsAll(grant.actions())) throw invalid("任务入口首期只开放查看、新增、修改和删除");
            } else if (relatedWrites.contains(grant.objectId())) {
                if (!Set.of(
                                ApplicationActionEnum.READ.getCode(),
                                ApplicationActionEnum.CREATE.getCode(),
                                ApplicationActionEnum.UPDATE.getCode())
                        .containsAll(grant.actions())) throw invalid("关联录入对象仅允许查看、新增和修改，不开放删除独立数据");
            } else if (!grant.actions().equals(Set.of(ApplicationActionEnum.READ.getCode()))
                    || !grant.writeFields().isEmpty()
                    || !grant.writeDetails().isEmpty()
                    || !grant.writeRelations().isEmpty()) throw invalid("入口依赖对象只允许必要的读取，不能跨对象写入");
        }
        if (main == null) throw invalid("任务入口必须配置主对象的办理范围");
        if ((main.actions().contains(ApplicationActionEnum.CREATE.getCode())
                        || main.actions().contains(ApplicationActionEnum.UPDATE.getCode()))
                && config.formId() == null) throw invalid("新增或修改入口必须选择表单");
        if (mode == TaskEntryModeEnum.FORM
                && (!main.actions().contains(ApplicationActionEnum.CREATE.getCode())
                        || main.actions().contains(ApplicationActionEnum.UPDATE.getCode())
                        || main.actions().contains(ApplicationActionEnum.DELETE.getCode())))
            throw invalid("直接填写入口仅允许新增和必要读取");
        // 应用定义里落的是规范化后的入口范围：死 ID 去掉、「全部」原样保留。
        return new TaskEntries.Config(
                config.objectId(),
                config.viewId(),
                config.formId(),
                config.mode(),
                config.category(),
                config.description(),
                config.icon(),
                config.sortOrder(),
                List.copyOf(limits));
    }

    private <T> T decode(
            Map<String, ApplicationCenter.Resource> resources,
            String id,
            ApplicationResourceKindEnum kind,
            Class<T> type) {
        var resource = resources.get(id);
        if (resource == null || !kind.matches(resource.kind())) throw invalid("任务入口引用的列表或表单不存在");
        return json.convertValue(resource.config(), type);
    }
}
