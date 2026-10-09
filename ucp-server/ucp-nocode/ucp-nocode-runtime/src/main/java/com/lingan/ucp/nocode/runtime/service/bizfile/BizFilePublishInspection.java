package com.lingan.ucp.nocode.runtime.service.bizfile;

import com.lingan.ucp.nocode.api.BusinessFilePublishInspection;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.DataCenter.BusinessFilePolicy;
import com.lingan.ucp.nocode.api.DataCenter.Definition;
import com.lingan.ucp.nocode.api.DataCenter.Detail;
import com.lingan.ucp.nocode.api.DataCenter.FieldOptions;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.enums.PublishCheckEnum;
import com.lingan.ucp.nocode.runtime.dal.mapper.BizDirectoryBindingMapper;
import com.lingan.ucp.nocode.runtime.dal.mapper.BizUploadSessionMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 业务文件发布预检实现
 *
 * <p>检查项：参与字段携带文件默认值时阻断发布（默认附件未建立独立受保护材料链路）； 在途上传会话与规则变更影响范围作为提示输出，规则版本按记录固定，已归档记录不随发布搬迁。
 */
@Component
public class BizFilePublishInspection implements BusinessFilePublishInspection {
    @Resource private BizUploadSessionMapper uploadSessions;
    @Resource private BizDirectoryBindingMapper directories;

    @Override
    public List<DataCenter.Check> inspect(String objectId, Definition draft, Definition published) {
        List<DataCenter.Check> checks = new ArrayList<>();
        if (objectId == null || draft == null) return checks;
        BusinessFilePolicy draftPolicy = policyOf(draft);
        BusinessFilePolicy publishedPolicy = policyOf(published);
        if (!BusinessFilePolicy.enabled(draftPolicy)) {
            if (BusinessFilePolicy.enabled(publishedPolicy)) {
                int records = directories.countRecordDirectories(objectId);
                checks.add(
                        new DataCenter.Check(
                                PublishCheckEnum.BUSINESS_FILE_SCOPE.getCode(),
                                "取消接入业务网盘后，参与字段的新上传走普通系统附件；已归档的 " + records + " 条记录文件保持原身份和访问规则",
                                false));
            }
            return checks;
        }
        for (String fieldId : draftPolicy.fieldIds()) {
            FieldOptions options = fieldOptions(draft, fieldId);
            if (options != null
                    && options.defaultValue() != null
                    && !options.defaultValue().isBlank()
                    && !"[]".equals(options.defaultValue().trim())) {
                checks.add(
                        new DataCenter.Check(
                                PublishCheckEnum.BUSINESS_FILE_DEFAULT.getCode(),
                                "字段「" + fieldName(draft, fieldId) + "」配置了默认文件，不能接入业务网盘，请先清除默认值",
                                true));
            }
        }
        int pending = uploadSessions.countTemporaryByObject(objectId);
        if (pending > 0) {
            checks.add(
                    new DataCenter.Check(
                            PublishCheckEnum.BUSINESS_FILE_SESSIONS.getCode(),
                            "当前有 " + pending + " 个未完成的业务文件上传会话，发布后保存将按新规则归档",
                            false));
        }
        if (BusinessFilePolicy.enabled(publishedPolicy)) {
            int records = directories.countRecordDirectories(objectId);
            if (records > 0) {
                checks.add(
                        new DataCenter.Check(
                                PublishCheckEnum.BUSINESS_FILE_SCOPE.getCode(),
                                "新目录规则仅用于新增记录和首次归档的记录，已归档的 " + records + " 条记录沿用原规则版本",
                                false));
            }
        }
        return checks;
    }

    private BusinessFilePolicy policyOf(Definition definition) {
        return definition == null || definition.settings() == null
                ? null
                : definition.settings().businessFilePolicy();
    }

    private FieldOptions fieldOptions(Definition definition, String fieldId) {
        FieldOptions options = definition.fieldOptions().get(fieldId);
        if (options != null) return options;
        for (Detail detail : definition.details()) {
            FieldOptions detailOptions = detail.fieldOptions().get(fieldId);
            if (detailOptions != null) return detailOptions;
        }
        return null;
    }

    private String fieldName(Definition definition, String fieldId) {
        FieldDefinition field =
                definition.fields().stream()
                        .filter(f -> f.id().equals(fieldId))
                        .findFirst()
                        .orElse(null);
        if (field == null) {
            for (Detail detail : definition.details()) {
                field =
                        detail.fields().stream()
                                .filter(f -> f.id().equals(fieldId))
                                .findFirst()
                                .orElse(null);
                if (field != null) break;
            }
        }
        return field == null ? fieldId : field.name();
    }
}
