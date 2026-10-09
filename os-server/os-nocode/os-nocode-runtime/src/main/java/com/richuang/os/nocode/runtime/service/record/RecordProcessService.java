package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.module.bpm.api.event.*;
import com.richuang.os.module.bpm.api.task.BpmProcessInstanceApi;
import com.richuang.os.module.bpm.api.task.dto.BpmProcessInstanceCreateReqDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.dal.dataobject.*;
import com.richuang.os.nocode.application.dal.mapper.*;
import com.richuang.os.nocode.application.service.resource.ApplicationProcessDefinition;
import com.richuang.os.nocode.enums.ApplicationProcessStateEnum;

import jakarta.annotation.Resource;

import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Service;

import java.util.*;

/** 只保存业务与流程关联，流程运行和审批全部交给底座 BPM。由记录服务持有主记录锁与事务。 */
@Service
public class RecordProcessService implements ApplicationListener<BpmProcessInstanceStatusEvent> {
    @Resource private RecordProcessMapper links;

    @Resource
    private com.richuang.os.nocode.work.service.submission.WorkSubmissionService submissions;

    @Resource private ApplicationProcessDefinition definitions;
    @Resource private BpmProcessInstanceApi instances;

    @Resource
    private org.springframework.beans.factory.ObjectProvider<
                    com.richuang.os.nocode.runtime.dal.mapper.HandlingRequestMapper>
            handling;

    public boolean running(String object, String record) {
        return links.running(Long.parseLong(object), record) > 0
                || submissions.protectsFlowRecord(object, record)
                || handling.getIfAvailable() != null
                        && handling.getObject()
                                .protects(
                                        object,
                                        record,
                                        com.richuang.os.nocode.runtime.service.handling
                                                .HandlingWriteScope.requestId());
    }

    public void requireIdle(String object, String record) {
        if (running(object, record)) throw invalid("记录受流程保护，暂不能修改、删除或重复发起流程");
    }

    public void start(
            ApplicationCenter.Published release,
            DataCenter.Definition object,
            ApplicationCenter.Resource resource,
            ApplicationBusiness.Action action,
            ApplicationRecords.Row record,
            long actor) {
        requireIdle(object.objectId(), record.id());
        var definition = definitions.require(action.processDefinitionId());
        Map<String, Object> variables = new LinkedHashMap<>();
        for (var entry : action.variables().entrySet()) {
            if (!record.permissions().readFields().contains(entry.getValue()))
                throw invalid("没有读取流程所需业务字段的权限");
            variables.put(entry.getKey(), record.values().get(entry.getValue()));
        }
        var ref =
                release.definition().objects().stream()
                        .filter(r -> r.objectId().equals(object.objectId()))
                        .findFirst()
                        .orElseThrow();
        var link = new NocodeRecordProcessDO();
        link.setApplicationId(Long.valueOf(release.application().id()));
        link.setApplicationVersion(release.versionNo());
        link.setObjectId(Long.valueOf(object.objectId()));
        link.setObjectVersion(ref.versionNo());
        link.setRecordId(record.id());
        link.setActionId(resource.id());
        link.setName(resource.name());
        link.setBusinessKey("nocode:" + UUID.randomUUID());
        link.setProcessDefinitionId(definition.getId());
        link.setProcessDefinitionKey(definition.getKey());
        link.setStatus(ApplicationProcessStateEnum.RUNNING.getCode());
        // 先登记关联：自动结束的流程会在 createProcessInstance 返回前同步发出终态事件。
        links.create(link, Long.toString(actor));
        var request = new BpmProcessInstanceCreateReqDTO();
        request.setBusinessKey(link.getBusinessKey());
        request.setProcessDefinitionId(definition.getId());
        request.setVariables(variables);
        String instance = instances.createProcessInstance(actor, request);
        if (instance == null || instance.isBlank() || links.attach(link.getId(), instance) != 1)
            throw invalid("流程实例关联失败");
    }

    public List<ApplicationRecords.Process> history(String app, String object, String record) {
        if (com.richuang.os.nocode.runtime.service.maintenance.ObjectMaintenanceScope.active(app))
            return List.of();
        return links.history(Long.parseLong(app), Long.parseLong(object), record).stream()
                .map(
                        p ->
                                new ApplicationRecords.Process(
                                        p.getBusinessKey(),
                                        p.getName(),
                                        p.getProcessInstanceId(),
                                        p.getStatus(),
                                        p.getCreateTime(),
                                        p.getEndTime()))
                .toList();
    }

    /** 只解析本模块生成的业务键；后续仍必须通过记录权限校验。 */
    public NocodeRecordProcessDO locate(String key) {
        if (key == null || !key.matches("nocode:[a-f0-9-]{36}")) throw invalid("流程业务记录不存在");
        var link = links.byBusinessKey(key);
        if (link == null) throw invalid("流程业务记录不存在");
        return link;
    }

    @Override
    public void onApplicationEvent(BpmProcessInstanceStatusEvent event) {
        if (event.getBusinessKey() == null
                || !event.getBusinessKey().startsWith("nocode:")
                || event.getId() == null
                || event.getProcessDefinitionKey() == null
                || event.getStatus() == null) return;
        var status =
                switch (event.getStatus()) {
                    case BpmProcessInstanceStatus.APPROVED -> ApplicationProcessStateEnum.APPROVED;
                    case BpmProcessInstanceStatus.REJECTED -> ApplicationProcessStateEnum.REJECTED;
                    case BpmProcessInstanceStatus.CANCELED -> ApplicationProcessStateEnum.CANCELED;
                    default -> null;
                };
        // 核对业务键、流程标识和已关联实例，终态事件幂等；不执行事件中的任何业务变量。
        if (status != null)
            links.complete(
                    event.getBusinessKey(),
                    event.getProcessDefinitionKey(),
                    event.getId(),
                    status.getCode());
    }
}
