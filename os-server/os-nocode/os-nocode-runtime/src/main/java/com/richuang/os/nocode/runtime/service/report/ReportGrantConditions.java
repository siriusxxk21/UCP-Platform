package com.richuang.os.nocode.runtime.service.report;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.runtime.dal.support.RuntimeConditionSql;
import com.richuang.os.nocode.runtime.service.access.ScopeConditions;
import com.richuang.os.nocode.runtime.service.record.RuntimeSchema;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.*;

/** 每个被查询字段分别约束可见记录，保留行条件与字段授权的绑定；过滤及关联连接键同样受控。 */
@Component
public class ReportGrantConditions {
    @Resource private ScopeConditions scopes;
    @Resource private RuntimeConditionSql sql;

    public QueryWrapper<Object> compile(
            DataCenter.Definition definition,
            RuntimeSchema.Table table,
            List<ObjectGrant> grants,
            Set<String> fields,
            Map<String, Object> identity,
            long actor,
            String alias,
            String parameterAlias) {
        return compile(
                definition, table, grants, fields, identity, actor, alias, parameterAlias, false);
    }

    /** 导出同时取 READ 与 EXPORT 的行范围交集，字段授权仍绑定同一成员规则。 */
    public QueryWrapper<Object> compile(
            DataCenter.Definition definition,
            RuntimeSchema.Table table,
            List<ObjectGrant> grants,
            Set<String> fields,
            Map<String, Object> identity,
            long actor,
            String alias,
            String parameterAlias,
            boolean exporting) {
        return compile(
                definition,
                table,
                grants,
                fields,
                identity,
                actor,
                alias,
                parameterAlias,
                exporting,
                null);
    }

    /** 数据集层与指定应用层各自成员 OR，逐使用字段再取两层 AND，不合并授权列表。 */
    public QueryWrapper<Object> compile(
            DataCenter.Definition definition,
            RuntimeSchema.Table table,
            List<ObjectGrant> grants,
            Set<String> fields,
            Map<String, Object> identity,
            long actor,
            String alias,
            String parameterAlias,
            boolean exporting,
            List<ObjectGrant> applicationGrants) {
        if (exporting && applicationGrants != null)
            applicationGrants =
                    applicationGrants.stream()
                            .filter(
                                    g ->
                                            g.actions()
                                                    .contains(
                                                            ApplicationActionEnum.EXPORT.getCode()))
                            .toList();
        if (exporting)
            grants =
                    grants.stream()
                            .filter(
                                    g ->
                                            g.actions()
                                                    .contains(
                                                            ApplicationActionEnum.EXPORT.getCode()))
                            .toList();
        QueryWrapper<Object> result = new QueryWrapper<>();
        result.setParamAlias(parameterAlias);
        // COUNT(*) 仍要求记录权限；有字段时取每个字段允许记录的交集。
        if (fields.isEmpty()) {
            append(result, definition, table, grants, identity, actor, alias, exporting);
            if (applicationGrants != null)
                append(
                        result,
                        definition,
                        table,
                        applicationGrants,
                        identity,
                        actor,
                        alias,
                        exporting);
        }
        for (String field : fields) {
            List<ObjectGrant> allowed =
                    grants.stream().filter(grant -> grant.readFields().contains(field)).toList();
            append(result, definition, table, allowed, identity, actor, alias, exporting);
            if (applicationGrants != null) {
                List<ObjectGrant> applicationAllowed =
                        applicationGrants.stream()
                                .filter(grant -> grant.readFields().contains(field))
                                .toList();
                append(
                        result,
                        definition,
                        table,
                        applicationAllowed,
                        identity,
                        actor,
                        alias,
                        exporting);
            }
        }
        return result;
    }

    private void append(
            QueryWrapper<Object> where,
            DataCenter.Definition definition,
            RuntimeSchema.Table table,
            List<ObjectGrant> grants,
            Map<String, Object> identity,
            long actor,
            String alias,
            boolean exporting) {
        List<ObjectGrant> readable =
                grants.stream()
                        .filter(
                                grant ->
                                        grant.actions()
                                                .contains(ApplicationActionEnum.READ.getCode()))
                        .toList();
        if (readable.isEmpty())
            throw new AccessDeniedException(exporting ? "没有查询字段的导出权限" : "没有查询字段的查看权限");
        where.nested(
                group -> {
                    boolean first = true;
                    for (ObjectGrant grant : readable) {
                        if (!first) group.or();
                        first = false;
                        group.nested(
                                rule -> {
                                    if (ApplicationScopeEnum.OWN.matches(grant.scope()))
                                        rule.eq(sql.creator(alias), Long.toString(actor));
                                    DataScope scope =
                                            grant.actionScopes()
                                                    .get(ApplicationActionEnum.READ.getCode());
                                    if (scope != null) {
                                        scope.validateEffective(definition);
                                        scopes.append(
                                                rule, scope, definition, table, identity, alias);
                                    }
                                    if (exporting) {
                                        DataScope exportScope =
                                                grant.actionScopes()
                                                        .get(
                                                                ApplicationActionEnum.EXPORT
                                                                        .getCode());
                                        if (exportScope != null) {
                                            exportScope.validateEffective(definition);
                                            scopes.append(
                                                    rule,
                                                    exportScope,
                                                    definition,
                                                    table,
                                                    identity,
                                                    alias);
                                        }
                                    }
                                    if (scope == null
                                            && ApplicationScopeEnum.ALL.matches(grant.scope()))
                                        rule.apply(sql.alwaysTrue());
                                });
                    }
                });
    }
}
