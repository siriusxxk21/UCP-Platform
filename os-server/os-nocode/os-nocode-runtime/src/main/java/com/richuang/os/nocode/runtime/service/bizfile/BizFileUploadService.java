package com.richuang.os.nocode.runtime.service.bizfile;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.core.util.StrUtil;

import com.richuang.os.module.drive.api.bizfile.DriveBizFileApi;
import com.richuang.os.module.drive.api.bizfile.dto.DriveBizTemporaryContent;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.runtime.dal.dataobject.BizUploadSessionDO;
import com.richuang.os.nocode.runtime.service.maintenance.ObjectDataMaintenanceService;
import com.richuang.os.nocode.runtime.service.record.RecordQueryAccess;
import com.richuang.os.nocode.runtime.service.record.RecordWriteService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;

/**
 * 业务附件临时上传 Service
 *
 * <p>上传发生在业务保存之前：先按入口重验可新增/可修改与字段可写，再写入受保护内容并登记上传会话； 记录保存时沿用同一规则校验归属并建立网盘节点。上传阶段不生成目录与节点，
 * 取消表单或保存失败不产生可见业务目录。临时内容仅上传会话本人可读，保存成功后改经业务内容端点按完整授权链读取。
 */
@Service
public class BizFileUploadService {

    /** 单个附件上界：与业务附件控件保持一致，服务端必须校验；对象规则收紧在后续版本补充 */
    private static final long MAX_FILE_BYTES = 50L * 1024 * 1024;

    @Resource private RecordQueryAccess records;
    @Resource private RecordWriteService writes;
    @Resource private ObjectDataMaintenanceService maintenance;
    @Resource private BizUploadSessionService sessions;
    @Resource private DriveBizFileApi driveFiles;
    @Resource private DataObjectApi objects;

    /** 对象设计器只向具备数据对象管理权的用户暴露业务空间稳定编号。 */
    public List<BusinessFiles.ConfigSpace> configSpaces(long actor) {
        return maintenance.read(
                actor,
                () ->
                        driveFiles.listBusinessSpaces().stream()
                                .map(
                                        space ->
                                                new BusinessFiles.ConfigSpace(
                                                        space.id(), space.name(), space.status()))
                                .toList());
    }

    /**
     * 临时上传：入口、字段身份与写入能力全部通过后写入内容并登记会话
     *
     * <p>新增记录按可新增能力判定，已有记录按该记录的修改能力判定并确认记录状态允许修改； 最终保存仍检查完整业务规则与上传会话归属（A12）。
     */
    public BusinessFiles.Uploaded upload(
            BusinessFiles.UploadQuery request, long actor, InputStream content) {
        if (request == null) throw invalid("业务文件上传请求不能为空");
        requireContent(request);
        if (request.size() <= 0) throw invalid("文件内容为空");
        if (request.size() > MAX_FILE_BYTES) throw invalid("单个文件不能超过 50MB");
        DataCenter.Definition definition =
                StrUtil.isBlank(request.applicationId())
                        ? maintenance.read(
                                actor, () -> records.definition(null, request.objectId(), actor))
                        : applicationDefinition(request, actor);
        DataCenter.BusinessFilePolicy businessPolicy =
                requireParticipatingField(definition, request);
        // 内容落存储前先解析并校验目标空间；已停用空间不产生新的临时内容。
        driveFiles.ensureBusinessSpace(businessPolicy.spaceId(), businessPolicy.spaceName(), actor);
        Long fileId =
                sessions.uploadTemporary(
                        actor,
                        request.objectId(),
                        request.fieldId(),
                        request.recordId(),
                        request.sessionKey(),
                        request.idempotencyKey(),
                        request.fileName(),
                        request.contentType(),
                        request.size(),
                        content);
        return new BusinessFiles.Uploaded(
                fileId, request.fileName(), request.size(), request.contentType());
    }

    /** 续期：本人该会话键下的临时上传顺延有效期；返回 false 表示没有有效会话（已过期需重新上传） */
    public boolean renew(String sessionKey, long actor) {
        return sessions.renew(sessionKey, actor);
    }

    /** 临时内容元信息：仅上传会话本人在有效期内可读，不使用业务记录授权 */
    public BusinessFiles.TemporaryContent temporaryContent(
            BusinessFiles.TemporaryQuery request, long actor) {
        if (request == null) throw invalid("临时文件读取请求不能为空");
        if (StrUtil.isBlank(request.objectId())) throw invalid("业务文件必须指定数据对象");
        if (StrUtil.isBlank(request.fieldId())) throw invalid("业务文件必须指定附件字段");
        BizUploadSessionDO session =
                sessions.requireTemporary(
                        actor,
                        request.objectId(),
                        request.fieldId(),
                        request.sessionKey(),
                        request.fileId());
        DriveBizTemporaryContent file = driveFiles.temporaryContentInfo(session.getFileId());
        return new BusinessFiles.TemporaryContent(
                file.fileId(), file.name(), file.size(), file.mimeType(), file.length());
    }

    /** 打开已确认会话归属的临时内容流；调用方负责关闭 */
    public InputStream temporaryStream(BusinessFiles.TemporaryContent content, long offset) {
        return driveFiles.openTemporaryContent(content.fileId(), offset);
    }

    /** 应用入口：发布版本校验、应用准入与逐记录写入能力（新建走 CREATE，编辑走 UPDATE 并确认记录状态） */
    private DataCenter.Definition applicationDefinition(
            BusinessFiles.UploadQuery request, long actor) {
        DataCenter.Definition definition =
                records.definition(request.applicationId(), request.objectId(), actor);
        ApplicationAuthorization.Capabilities caps =
                writes.workWriteCapabilities(
                        request.applicationId(), request.objectId(), request.recordId(), actor);
        if (StrUtil.isBlank(request.detailId())) {
            if (!caps.writeFields().contains(request.fieldId())) throw invalid("没有此附件字段的填写权限");
        } else if (!caps.writeDetails().contains(request.detailId())) {
            throw invalid("没有此明细区的填写权限");
        }
        return definition;
    }

    /**
     * 上传字段必须属于对象当前生效的业务文件规则的接入字段，并按主表/明细区限定位置
     *
     * <p>规则取对象当前发布版本，与保存绑定同一来源；应用固定引用版本的旧规则只决定可用字段，不决定上传归属。
     */
    private DataCenter.BusinessFilePolicy requireParticipatingField(
            DataCenter.Definition definition, BusinessFiles.UploadQuery request) {
        DataCenter.BusinessFilePolicy policy = publishedPolicy(request.objectId());
        if (!DataCenter.BusinessFilePolicy.enabled(policy)
                || !policy.fieldIds().contains(request.fieldId())) {
            throw invalid("该字段未接入业务文件，请使用普通附件上传");
        }
        if (StrUtil.isBlank(request.detailId())) {
            if (definition.fields().stream().noneMatch(f -> f.id().equals(request.fieldId()))) {
                throw invalid("附件字段不属于当前对象主表");
            }
            return policy;
        }
        DataCenter.Detail detail =
                definition.details().stream()
                        .filter(d -> d.id().equals(request.detailId()))
                        .findFirst()
                        .orElseThrow(() -> invalid("附件字段所属明细区不存在"));
        if (detail.fields().stream().noneMatch(f -> f.id().equals(request.fieldId()))) {
            throw invalid("附件字段不属于所选明细区");
        }
        return policy;
    }

    /** 位置身份：对象、字段与会话键缺一不可，不接受只凭文件编号登记 */
    private static void requireContent(BusinessFiles.UploadQuery request) {
        if (StrUtil.isBlank(request.objectId())) throw invalid("业务文件必须指定数据对象");
        if (StrUtil.isBlank(request.fieldId())) throw invalid("业务文件必须指定附件字段");
        if (StrUtil.isBlank(request.fileName())) throw invalid("文件名称不能为空");
        if (StrUtil.isBlank(request.sessionKey())) throw invalid("上传会话标识无效");
    }

    /** 对象当前生效的业务文件规则：上传校验与保存绑定共用同一来源，避免应用固定版本与对象新版本脱节时归属不一致 */
    private DataCenter.BusinessFilePolicy publishedPolicy(String objectId) {
        DataCenter.Definition published = objects.getPublished(objectId);
        return published.settings() == null ? null : published.settings().businessFilePolicy();
    }
}
