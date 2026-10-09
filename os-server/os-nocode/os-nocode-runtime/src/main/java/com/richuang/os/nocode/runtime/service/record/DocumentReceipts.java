package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.databind.*;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.runtime.dal.dataobject.DocumentReceiptDO;
import com.richuang.os.nocode.runtime.dal.mapper.DocumentReceiptMapper;

import jakarta.annotation.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/** 收据不授予业务数据权限；调用方在返回快照前核验当前应用、记录及字段授权。 */
@Service
public class DocumentReceipts {
    @Resource private DocumentReceiptMapper receipts;
    @Resource private ObjectMapper json;
    private ObjectMapper canonical;

    @PostConstruct
    void initialize() {
        canonical = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    public DocumentReceiptDO successful(Save command, long actor) {
        if (command.requestKey() == null) return null;
        requireTransaction();
        validate(command.requestKey());
        receipts.lock(
                actor
                        + ":"
                        + command.applicationId()
                        + ":"
                        + command.objectId()
                        + ":"
                        + command.requestKey());
        var row = find(command.applicationId(), command.objectId(), command.requestKey(), actor);
        if (row != null && !row.getRequestDigest().equals(digest(command)))
            throw invalid("同一个请求标识不能用于不同的保存内容");
        return row;
    }

    public DocumentReceiptDO find(String app, String object, String key, long actor) {
        validate(key);
        return receipts.find(
                Long.toString(actor), app, object, DocumentOperationEnum.SAVE.getCode(), key);
    }

    public void append(
            Save command,
            Aggregate result,
            DataCenter.Definition definition,
            String operationId,
            long actor) {
        if (command.requestKey() == null) return;
        requireTransaction();
        var row = new DocumentReceiptDO();
        row.setApplicationId(
                command.applicationId() == null ? null : Long.valueOf(command.applicationId()));
        row.setObjectId(Long.valueOf(command.objectId()));
        row.setOperation(DocumentOperationEnum.SAVE.getCode());
        row.setRequestKey(command.requestKey());
        row.setRequestDigest(digest(command));
        row.setOperationId(operationId);
        row.setRecordId(result.record().id());
        row.setRecordRevision(result.record().revision());
        row.setPolicyVersion(policyVersion(definition));
        row.setResultJson(encode(result));
        receipts.append(row, Long.toString(actor));
    }

    public Aggregate decode(DocumentReceiptDO row) {
        try {
            return json.readValue(row.getResultJson(), Aggregate.class);
        } catch (java.io.IOException e) {
            throw invalid("保存收据暂时无法读取，请保留原请求标识");
        }
    }

    public String policyVersion(DataCenter.Definition d) {
        return DigestUtil.sha256Hex(
                encode(d.settings() == null ? null : d.settings().documentPolicy()));
    }

    /** 摘要只取客户端写入意图，排除展示标签、权限回显和每次会变化的服务端默认值。 */
    public String commandDigest(Save command) {
        return digest(command);
    }

    private String digest(Save command) {
        Map<String, List<Row>> details = null;
        if (command.details() != null) {
            details = new TreeMap<>();
            for (var group : command.details().entrySet()) {
                details.put(
                        group.getKey(),
                        group.getValue() == null
                                ? null
                                : group.getValue().stream()
                                        .map(
                                                row ->
                                                        row == null
                                                                ? null
                                                                : new Row(
                                                                        row.id(),
                                                                        row.revision(),
                                                                        row.values(),
                                                                        null,
                                                                        null,
                                                                        row.clientRowKey()))
                                        .toList());
            }
        }
        var intent =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        canonical.valueToTree(
                                new Save(
                                        command.applicationId(),
                                        command.objectId(),
                                        command.id(),
                                        command.expectedRevision(),
                                        command.values(),
                                        details,
                                        command.relations(),
                                        command.context(),
                                        command.formId(),
                                        null,
                                        command.actionCode(),
                                        command.relatedRecords()));
        // 旧普通表单请求没有 relatedRecords 字段；空关联不能改变历史收据摘要。
        if (command.relatedRecords() == null || command.relatedRecords().isEmpty())
            intent.remove("relatedRecords");
        return DigestUtil.sha256Hex(encode(intent));
    }

    private String encode(Object value) {
        try {
            return canonical.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw invalid("整单请求或收据无法编码");
        }
    }

    private static void validate(String key) {
        if (key == null || !key.matches("[A-Za-z0-9:_-]{8,128}")) throw invalid("保存请求标识无效");
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw invalid("保存收据必须与业务写入处于同一事务");
    }
}
