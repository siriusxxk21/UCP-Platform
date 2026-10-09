package com.lingan.ucp.nocode.runtime.service.taskcenter;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.CONFLICT;
import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.TaskCenter.Create;
import com.lingan.ucp.nocode.api.TaskCenter.Detail;
import com.lingan.ucp.nocode.api.TaskCenter.Draft;
import com.lingan.ucp.nocode.api.TaskCenter.DraftPublish;
import com.lingan.ucp.nocode.api.TaskCenter.DraftRef;
import com.lingan.ucp.nocode.api.TaskCenter.DraftSave;
import com.lingan.ucp.nocode.api.TaskCenter.DraftSummary;
import com.lingan.ucp.nocode.runtime.dal.dataobject.TaskLaunchDraftDO;
import com.lingan.ucp.nocode.runtime.dal.mapper.TaskLaunchDraftMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** 私有任务草稿与原子发布；保存不解析表单、不创建业务数据，也不提前校验任务可执行性。 */
@Service
public class TaskLaunchDraftServiceImpl implements TaskLaunchDraftService {
    private static final int MAX_CONTENT_BYTES = 2_000_000;
    private static final int MAX_JSON_DEPTH = 64;
    private static final int MAX_JSON_NODES = 100_000;

    @Resource private TaskLaunchDraftMapper drafts;
    @Resource private TaskCenterService tasks;
    @Resource private ObjectMapper json;
    @Resource private PlatformTransactionManager transactionManager;
    private TransactionTemplate tx;

    @PostConstruct
    void initialize() {
        tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public List<DraftSummary> list(long actor) {
        return drafts.listOwned(actor(actor));
    }

    @Override
    public Draft get(String id, long actor) {
        return view(owned(id, actor(actor), false));
    }

    @Override
    public Draft save(DraftSave command, long actor) {
        if (command == null || command.content() == null) throw invalid("缺少任务草稿内容");
        String owner = actor(actor);
        String content = encode(command.content());
        return tx.execute(
                status -> {
                    if (command.id() == null) {
                        if (command.expectedRevision() != null) throw invalid("新任务草稿不能指定修订号");
                        return create(UUID.randomUUID().toString(), content, owner);
                    }
                    requireId(command.id());
                    if (Integer.valueOf(0).equals(command.expectedRevision())) {
                        drafts.lockCreate(command.id());
                        TaskLaunchDraftDO previous = drafts.getOwned(command.id(), owner, true);
                        if (previous == null) return create(command.id(), content, owner);
                        if (previous.getPublishedTaskId() != null
                                || !Integer.valueOf(1).equals(previous.getLockVersion())
                                || !sameContent(previous.getContentJson(), content))
                            throw conflict();
                        return view(previous);
                    }
                    TaskLaunchDraftDO row = owned(command.id(), owner, true);
                    requireEditable(row, command.expectedRevision());
                    row.setContentJson(content);
                    if (drafts.saveDraft(row, owner, command.expectedRevision()) != 1)
                        throw conflict();
                    return view(owned(row.getId(), owner, false));
                });
    }

    @Override
    public void delete(DraftRef command, long actor) {
        if (command == null) throw invalid("缺少任务草稿标识");
        String owner = actor(actor);
        tx.executeWithoutResult(
                status -> {
                    TaskLaunchDraftDO row = owned(command.id(), owner, true);
                    requireEditable(row, command.expectedRevision());
                    if (drafts.deleteDraft(row.getId(), owner, command.expectedRevision()) != 1)
                        throw conflict();
                });
    }

    @Override
    public Detail publish(DraftPublish command, long actor) {
        if (command == null) throw invalid("缺少任务草稿发布参数");
        String owner = actor(actor);
        String key = TaskGraph.text(command.requestKey(), "请求标识", 120, true);
        return tx.execute(
                status -> {
                    // 先锁本人草稿，再进入统一任务创建事务；内层 REQUIRED 事务加入同一次提交。
                    TaskLaunchDraftDO row = owned(command.id(), owner, true);
                    if (row.getPublishedTaskId() != null) {
                        if (!Objects.equals(row.getPublishKey(), key))
                            throw invalid("此草稿已加入任务池，不能更换请求标识重复加入");
                        return tasks.detail(row.getPublishedTaskId(), actor);
                    }
                    requireEditable(row, command.expectedRevision());
                    Create saved = read(row.getContentJson());
                    Create publishing =
                            new Create(
                                    saved.task(),
                                    saved.parentId(),
                                    saved.templateId(),
                                    saved.templateVersion(),
                                    saved.project(),
                                    saved.business(),
                                    saved.existingRecord(),
                                    key,
                                    saved.nodes(),
                                    saved.kind(),
                                    saved.applicationId(),
                                    saved.plannedStart());
                    Detail result = tasks.create(publishing, actor);
                    row.setPublishedTaskId(result.task().id());
                    row.setPublishKey(key);
                    // 更新失败向外抛出异常，让先前发生的任务和业务写入一并回滚。
                    if (drafts.markPublished(row, owner, command.expectedRevision()) != 1)
                        throw conflict();
                    return result;
                });
    }

    private String actor(long actor) {
        if (actor <= 0) throw invalid("任务草稿需要登录后操作");
        return Long.toString(actor);
    }

    private void requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 64) throw invalid("任务草稿不存在或无权访问");
    }

    private TaskLaunchDraftDO owned(String id, String owner, boolean lock) {
        requireId(id);
        TaskLaunchDraftDO row = drafts.getOwned(id, owner, lock);
        if (row == null) throw invalid("任务草稿不存在或无权访问");
        return row;
    }

    private Draft create(String id, String content, String owner) {
        TaskLaunchDraftDO row = new TaskLaunchDraftDO();
        row.setId(id);
        row.setContentJson(content);
        // 他人的同名标识或已删除草稿不能复活，也不能借首次保存探测所有者。
        if (drafts.createDraft(row, owner) != 1) throw invalid("任务草稿不存在或无权访问");
        return view(owned(id, owner, false));
    }

    private boolean sameContent(String previous, String current) {
        try {
            return json.readTree(previous).equals(json.readTree(current));
        } catch (JsonProcessingException ex) {
            throw invalid("任务草稿内容无法读取");
        }
    }

    private void requireEditable(TaskLaunchDraftDO row, Integer expected) {
        if (row.getPublishedTaskId() != null) throw invalid("此草稿已发起任务，不能修改或删除");
        if (expected == null || expected < 1 || !Objects.equals(row.getLockVersion(), expected))
            throw conflict();
    }

    private Draft view(TaskLaunchDraftDO row) {
        return new Draft(
                row.getId(),
                row.getLockVersion(),
                row.getUpdateTime(),
                read(row.getContentJson()),
                row.getPublishedTaskId());
    }

    private String encode(Create content) {
        try {
            String value = json.writeValueAsString(content);
            if (value.length() > MAX_CONTENT_BYTES
                    || value.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES)
                throw invalid("任务草稿内容超过 2 MB 大小限制");
            // 草稿允许缺名称、人员及未完成的依赖配置，仅限制动态业务值的嵌套和体积。
            validateStructure(json.readTree(value), 0, new int[1]);
            return value;
        } catch (JsonProcessingException ex) {
            throw invalid("任务草稿内容无法保存");
        }
    }

    private void validateStructure(JsonNode value, int depth, int[] visited) {
        if (depth > MAX_JSON_DEPTH || ++visited[0] > MAX_JSON_NODES) throw invalid("任务草稿内容结构超过限制");
        if (value.isTextual()) requireJsonText(value.textValue());
        if (value.isObject()) {
            Iterator<String> names = value.fieldNames();
            while (names.hasNext()) requireJsonText(names.next());
        }
        if (value.isContainerNode()) {
            for (JsonNode child : value) validateStructure(child, depth + 1, visited);
        }
    }

    private void requireJsonText(String text) {
        // PostgreSQL jsonb 无法读取 NUL 或孤立代理字符，不能让已保存的业务值破坏草稿列表查询。
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == 0 || Character.isLowSurrogate(character))
                throw invalid("任务草稿包含无法保存的字符");
            if (Character.isHighSurrogate(character)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index)))
                    throw invalid("任务草稿包含无法保存的字符");
            }
        }
    }

    private Create read(String content) {
        try {
            Create result = json.readValue(content, Create.class);
            if (result == null) throw invalid("任务草稿内容无法读取");
            return result;
        } catch (JsonProcessingException ex) {
            throw invalid("任务草稿内容无法读取");
        }
    }

    private ServiceException conflict() {
        return new ServiceException(CONFLICT, "任务草稿已被修改，请刷新后重试");
    }
}
