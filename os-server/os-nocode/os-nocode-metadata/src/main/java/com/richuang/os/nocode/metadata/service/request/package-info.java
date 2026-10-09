/**
 * 只读请求内的记忆化。ReadRequestMemo 只在显式开启的只读作用域里生效，按「当前事务」或「请求内的无事务段」
 * 分别保存已读到的发布定义、授权上限和物理表结构，不跨请求、不跨事务，写入路径完全不经过。
 */
package com.richuang.os.nocode.metadata.service.request;
