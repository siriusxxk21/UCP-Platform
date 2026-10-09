package com.lingan.ucp.module.drive.api.folder.dto;

/**
 * 限定子树的一次调用凭据：调用方已按自己的业务规则完成鉴权后，声明「在哪棵子树里、以什么角色、经由哪个业务来源」。
 *
 * @param spaceId 子树所在空间
 * @param rootEntryId 子树的根（一个普通目录节点）；客户端不得提交，由调用方在服务端解析
 * @param role VIEWER / EDITOR
 * @param originKey 必填（非空白，≤ 700 字）：这次调用是经由哪个业务来源进来的；对网盘是不透明字符串
 */
public record DriveFolderScope(Long spaceId, Long rootEntryId, String role, String originKey) {}
