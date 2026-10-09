package com.richuang.os.nocode.application.service.task;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.dal.mapper.ApplicationMapper;
import com.richuang.os.nocode.application.dal.mapper.TaskEntryAccessMapper;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.richuang.os.nocode.application.service.resource.ApplicationResourceValidator;
import com.richuang.os.nocode.application.service.sharing.ObjectGrantValidator;
import com.richuang.os.nocode.enums.ApplicationResourceKindEnum;
import com.richuang.os.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.richuang.os.nocode.metadata.service.object.DraftValidator;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 配置身份检查与应用头锁串联；入口成员复用应用授权的同一套验证。 */
@Service
public class TaskEntryPolicyServiceImpl implements TaskEntryPolicyService {
    @Resource private TaskEntryAccessMapper store;
    @Resource private ApplicationMapper appStore;
    @Resource private ObjectDraftMapper designLocks;
    @Resource private ApplicationService applications;
    @Resource private ApplicationAuthorizationService authorization;
    @Resource private ApplicationResourceValidator resources;
    @Resource private ObjectGrantValidator grants;
    @Resource private DraftValidator validator;
    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager transactionManager;

    @Override
    public TaskEntries.Policy get(String applicationId, String entryId) {
        if (entryId == null || entryId.isBlank() || entryId.length() > 80) throw invalid("缺少任务入口");
        var data = store.find(validator.id(applicationId, "应用"), entryId);
        if (data == null) return new TaskEntries.Policy(0, false, List.of());
        try {
            return new TaskEntries.Policy(
                    data.getLockVersion(),
                    data.getEnabled(),
                    json.readValue(
                            data.getPolicyJson(),
                            new TypeReference<List<ApplicationAuthorization.Member>>() {}));
        } catch (java.io.IOException e) {
            throw invalid("任务入口授权无法读取");
        }
    }

    @Override
    public TaskEntries.Policy save(TaskEntries.SavePolicy command, long actor) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> saveLocked(command, actor));
    }

    private TaskEntries.Policy saveLocked(TaskEntries.SavePolicy command, long actor) {
        if (command == null) throw invalid("缺少入口授权配置");
        // 与应用发布读取保持目录锁、设计锁、应用头锁的顺序。
        appStore.automationCatalogLock(false);
        designLocks.lockTableName("nocode-design-write");
        applications.requireDesigner(command.applicationId(), actor);
        long app = validator.id(command.applicationId(), "应用");
        if (appStore.lock(app, true) == null) throw invalid("应用不存在");
        var current = get(command.applicationId(), command.entryId());
        if (current.revision() != command.expectedRevision())
            throw new ServiceException(CONFLICT, "入口授权已变化，请刷新");
        var detail = applications.get(command.applicationId());
        var resource =
                detail.draft().resources().stream()
                        .filter(
                                r ->
                                        r.id().equals(command.entryId())
                                                && ApplicationResourceKindEnum.TASK_ENTRY.matches(
                                                        r.kind()))
                        .findFirst()
                        .orElseThrow(() -> invalid("请先保存任务入口到应用草稿"));
        var config = resources.decode(resource.config(), TaskEntries.Config.class);
        var members =
                command.members() == null
                        ? List.<ApplicationAuthorization.Member>of()
                        : command.members();
        // 落库的是规范化并收紧后的成员：先按应用上限，再按本入口的允许范围。
        List<ApplicationAuthorization.Member> stored = new ArrayList<>();
        for (var member : authorization.validateMembers(command.applicationId(), members)) {
            List<ApplicationAuthorization.ObjectGrant> objectGrants = new ArrayList<>();
            for (var grant : member.objects()) {
                var limit =
                        config.limits().stream()
                                .filter(g -> g.objectId().equals(grant.objectId()))
                                .findFirst()
                                .orElseThrow(() -> invalid("成员授权只能选择本入口的对象"));
                objectGrants.add(grants.within(grant, limit));
            }
            stored.add(
                    new ApplicationAuthorization.Member(
                            member.principalKind(), member.principalId(), objectGrants));
        }
        try {
            String encoded = json.writeValueAsString(stored);
            if (encoded.length() > 2_000_000) throw invalid("入口授权配置过大");
            store.save(app, command.entryId(), command.enabled(), encoded, Long.toString(actor));
            return get(command.applicationId(), command.entryId());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw invalid("入口授权无法保存");
        }
    }
}
