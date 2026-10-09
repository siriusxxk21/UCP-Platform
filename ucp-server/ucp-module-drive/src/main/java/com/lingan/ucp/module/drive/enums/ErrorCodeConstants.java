package com.lingan.ucp.module.drive.enums;

import com.lingan.ucp.framework.common.exception.ErrorCode;

/**
 * Drive 错误码枚举类
 *
 * <p>drive 模块，使用 1-010-000-000 段
 */
public interface ErrorCodeConstants {

    // ========== 空间 1-010-000-000 ==========
    ErrorCode SPACE_NOT_EXISTS = new ErrorCode(1_010_000_000, "网盘空间不存在");
    ErrorCode SPACE_PERSONAL_NOT_CREATE = new ErrorCode(1_010_000_001, "个人空间由系统自动建立，不支持手工创建");
    ErrorCode SPACE_PERSONAL_NOT_DELETE = new ErrorCode(1_010_000_002, "个人空间不支持删除");
    ErrorCode SPACE_DISABLED = new ErrorCode(1_010_000_003, "网盘空间已停用，暂不可访问");
    ErrorCode SPACE_NO_PERMISSION = new ErrorCode(1_010_000_004, "无权访问该网盘空间");
    ErrorCode SPACE_NOT_EMPTY = new ErrorCode(1_010_000_005, "空间内仍有内容，请先清空后再删除");
    ErrorCode SPACE_BIZ_FORBIDDEN = new ErrorCode(1_010_000_006, "业务空间内容由业务规则管理，请在对应业务表单中上传或维护");
    ErrorCode SPACE_NAME_DUPLICATE = new ErrorCode(1_010_000_007, "已存在同名业务空间，请更换名称");
    ErrorCode SPACE_BIZ_NAME_INVALID = new ErrorCode(1_010_000_008, "业务空间名称不能为空且不能超过64个字符");

    // ========== 节点 1-010-001-000 ==========
    ErrorCode ENTRY_NOT_EXISTS = new ErrorCode(1_010_001_000, "网盘节点不存在");
    ErrorCode ENTRY_NAME_DUPLICATE = new ErrorCode(1_010_001_001, "该目录下已存在同名目录");
    ErrorCode ENTRY_NAME_INVALID = new ErrorCode(1_010_001_002, "名称不能为空，且不能包含 / 或 \\ 等非法字符");
    ErrorCode ENTRY_PARENT_NOT_FOLDER = new ErrorCode(1_010_001_003, "目标位置不是目录");
    ErrorCode ENTRY_MOVE_TO_CHILD = new ErrorCode(1_010_001_004, "不能移动到自身或自己的子目录下");
    ErrorCode ENTRY_ALREADY_TRASHED = new ErrorCode(1_010_001_005, "节点已在回收站中");
    ErrorCode ENTRY_NOT_TRASHED = new ErrorCode(1_010_001_006, "节点不在回收站中");
    ErrorCode ENTRY_TRASH_CONFLICT = new ErrorCode(1_010_001_007, "原位置已有同名目录，请先重命名后再恢复");
    ErrorCode ENTRY_SPACE_NOT_MATCH = new ErrorCode(1_010_001_009, "节点与目标空间不一致，跨空间操作不支持");
    ErrorCode ENTRY_FILE_CONTENT_MISSING = new ErrorCode(1_010_001_010, "文件内容不存在，可能已被存储侧清理");
    ErrorCode ENTRY_UPDATE_CONFLICT = new ErrorCode(1_010_001_011, "节点已被他人修改，请刷新后重试");
    ErrorCode ENTRY_FILE_TOO_LARGE = new ErrorCode(1_010_001_012, "文件大小超过网盘单文件上限");
    ErrorCode ENTRY_MANAGED_FORBIDDEN = new ErrorCode(1_010_001_013, "该目录或文件由业务规则管理，请在对应业务表单中操作");
    ErrorCode BIZ_FILE_NOT_EXISTS = new ErrorCode(1_010_001_014, "业务文件不存在或已删除，请重新上传");
    ErrorCode ENTRY_SCOPE_ROOT_FORBIDDEN = new ErrorCode(1_010_001_015, "不能对这个文件夹本身做此操作");
    ErrorCode ENTRY_SCOPE_UNAVAILABLE = new ErrorCode(1_010_001_016, "文件夹不存在或已不可用");
    ErrorCode ENTRY_RESTORE_PARENT_GONE =
            new ErrorCode(1_010_001_017, "原来的位置已不存在，请联系网盘管理员从回收站恢复");
    ErrorCode ENTRY_ORDINARY_NAME_TAKEN =
            new ErrorCode(1_010_001_018, "业务空间里已经有一个同名的普通文件夹「{}」，请把它改名，或把对象的固定目录改个名字");
    ErrorCode ENTRY_SCOPE_NOT_OWN = new ErrorCode(1_010_001_019, "只能修改或删除经由这条记录放进去的文件");
    ErrorCode ENTRY_SCOPE_FOLDER_MIXED =
            new ErrorCode(1_010_001_020, "这个文件夹里有不是经由这条记录放进去的内容，不能移动或删除");

    // ========== 授权 1-010-002-000 ==========
    ErrorCode PERMISSION_NOT_EXISTS = new ErrorCode(1_010_002_000, "授权记录不存在");
    ErrorCode PERMISSION_DENIED = new ErrorCode(1_010_002_001, "无权执行该操作");
    ErrorCode PERMISSION_ROLE_INVALID = new ErrorCode(1_010_002_002, "授权角色不合法");
    ErrorCode PERMISSION_SUBJECT_INVALID = new ErrorCode(1_010_002_003, "授权主体不存在或已停用");
    ErrorCode PERMISSION_OWNER_NOT_EDIT = new ErrorCode(1_010_002_004, "空间归属主体的授权不支持修改或移除");

    // ========== 分享 1-010-003-000 ==========
    ErrorCode SHARE_NOT_EXISTS = new ErrorCode(1_010_003_000, "分享不存在");
    ErrorCode SHARE_INACTIVE = new ErrorCode(1_010_003_001, "分享已撤销或已到期");
    ErrorCode SHARE_SUBJECT_EMPTY = new ErrorCode(1_010_003_002, "分享接收人不能为空");
    ErrorCode SHARE_EXPIRE_INVALID = new ErrorCode(1_010_003_003, "分享有效期必须晚于当前时间");

    // ========== 用户标记 1-010-004-000 ==========
    ErrorCode MARK_NOT_EXISTS = new ErrorCode(1_010_004_000, "收藏记录不存在");

    // ========== 存储源 1-010-005-000 ==========
    ErrorCode STORAGE_CONFIG_INVALID = new ErrorCode(1_010_005_000, "请选择有效的本地、MinIO 或阿里云 OSS 存储配置");
}
