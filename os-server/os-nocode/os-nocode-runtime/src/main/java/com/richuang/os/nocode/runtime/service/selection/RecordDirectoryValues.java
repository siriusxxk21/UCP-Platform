package com.richuang.os.nocode.runtime.service.selection;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.module.bpm.api.definition.BpmUserGroupApi;
import com.richuang.os.module.infra.service.file.FileService;
import com.richuang.os.module.system.api.dept.DeptApi;
import com.richuang.os.module.system.api.dept.PostApi;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.nocode.enums.FieldTypeEnum;
import com.richuang.os.nocode.runtime.service.record.RuntimeSchema;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.*;

/** 底座选择器的服务端边界。新增/改变的 ID 必须有效，未修改的历史引用允许保留。 */
@Service
public class RecordDirectoryValues {
    @Resource private AdminUserApi users;
    @Resource private DeptApi departments;
    @Resource private PostApi posts;
    @Resource private BpmUserGroupApi groups;
    @Resource private FileService files;

    public void validate(
            RuntimeSchema.Table table, Map<String, Object> payload, Map<String, Object> previous) {
        Map<FieldTypeEnum, Set<Long>> ids = new EnumMap<>(FieldTypeEnum.class);
        for (var field : table.fields()) {
            String column = table.column(field);
            Object value = payload.get(column);
            var kind = FieldTypeEnum.fromCode(field.type());
            if (value == null || Objects.equals(value, previous.get(field.id()))) continue;
            switch (kind) {
                case USER, DEPARTMENT, POST, USER_GROUP ->
                        ids.computeIfAbsent(kind, k -> new HashSet<>()).add(id(value));
                case IMAGE, ATTACHMENT -> {
                    if (!(value instanceof List<?> list)) throw invalid("文件字段必须为已上传文件 ID 列表");
                    var old =
                            previous.get(field.id()) instanceof List<?> listValue
                                    ? listValue
                                    : List.of();
                    for (Object file : list) {
                        Long fileId = id(file);
                        if (!old.contains(file))
                            ids.computeIfAbsent(FieldTypeEnum.ATTACHMENT, k -> new HashSet<>())
                                    .add(fileId);
                    }
                }
                default -> {}
            }
        }
        for (var entry : ids.entrySet()) {
            if (entry.getValue().size() > 1000) throw invalid("单笔目录或文件引用过多");
            switch (entry.getKey()) {
                case USER -> users.validateUserList(entry.getValue());
                case DEPARTMENT -> departments.validateDeptList(entry.getValue());
                case POST -> posts.validPostList(entry.getValue());
                case USER_GROUP -> groups.validateUserGroups(entry.getValue());
                case ATTACHMENT -> {
                    var actual = files.getFiles(new ArrayList<>(entry.getValue()));
                    if (actual == null
                            || !actual.stream()
                                    .map(f -> f.getId())
                                    .collect(java.util.stream.Collectors.toSet())
                                    .containsAll(entry.getValue()))
                        throw invalid("附件不存在或已删除，请重新上传");
                }
                default -> throw invalid("不支持的目录类型");
            }
        }
    }

    private Long id(Object value) {
        try {
            if (value == null || !value.toString().matches("[1-9][0-9]{0,18}"))
                throw new IllegalArgumentException();
            return Long.valueOf(value.toString());
        } catch (IllegalArgumentException e) {
            throw invalid("目录或文件引用必须为有效 ID");
        }
    }
}
