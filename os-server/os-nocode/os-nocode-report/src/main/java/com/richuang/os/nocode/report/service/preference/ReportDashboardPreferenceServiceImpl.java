package com.richuang.os.nocode.report.service.preference;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.nocode.api.ReportDashboardPreferences;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.object.DraftValidator;
import com.richuang.os.nocode.report.dal.dataobject.*;
import com.richuang.os.nocode.report.dal.mapper.*;
import com.richuang.os.nocode.report.service.authorization.*;
import com.richuang.os.nocode.report.service.dashboard.ReportDashboardService;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.LongFunction;

/** 复用当前ACL和安全发布摘要；设计共享锁与头共享锁阻止撤权/停用穿透偏好写入。 */
@Service
public class ReportDashboardPreferenceServiceImpl implements ReportDashboardPreferenceService {
    @Resource private ReportDashboardPreferenceMapper preferences;
    @Resource private ReportDashboardMapper dashboards;
    @Resource private ReportDatasetMapper datasets;
    @Resource private ReportDashboardService dashboardService;
    @Resource private ReportResourceAccess access;
    @Resource private ReportPrincipals principals;
    @Resource private PermissionCommonApi permissions;
    @Resource private DraftValidator validator;
    @Resource private PlatformTransactionManager manager;

    @Override
    public PageResult<ReportDashboardPreferences.Item> page(
            int page, int size, String search, String view, long actor) {
        permission(actor);
        if (page < 1 || size < 1 || size > 100) throw invalid("偏好分页参数无效");
        String term = search == null ? "" : search.trim();
        if (term.length() > 80) throw invalid("搜索名称最多80字符");
        if (view != null
                && java.util.Arrays.stream(ReportDashboardPreferenceViewEnum.values())
                        .noneMatch(value -> value.matches(view)))
            throw invalid("偏好视图无效，请选择全部、收藏或最近访问");
        ReportDashboardPreferenceViewEnum kind =
                ReportDashboardPreferenceViewEnum.fromCode(
                        view == null ? ReportDashboardPreferenceViewEnum.ALL.getCode() : view);
        ReportPrincipals.Snapshot identity = principals.snapshot(actor);
        PageResult<ReportDashboardPreferences.Item> result =
                new TransactionTemplate(manager)
                        .execute(
                                tx -> {
                                    datasets.designReadLock();
                                    com.baomidou.mybatisplus.core.metadata.IPage<ReportDashboardDO>
                                            found =
                                                    preferences.page(
                                                            new Page<>(page, size),
                                                            actor,
                                                            identity.principals(),
                                                            "%"
                                                                    + term.replace("!", "!!")
                                                                            .replace("%", "!%")
                                                                            .replace("_", "!_")
                                                                    + "%",
                                                            kind.getCode());
                                    List<ReportDashboardPreferences.Item> items =
                                            found.getRecords().stream()
                                                    .map(
                                                            row -> {
                                                                ReportDashboardPreferences.State
                                                                        state =
                                                                                state(
                                                                                        preferences
                                                                                                .get(
                                                                                                        actor,
                                                                                                        row
                                                                                                                .getId()));
                                                                return new ReportDashboardPreferences
                                                                        .Item(
                                                                        dashboardService
                                                                                .publishedSummary(
                                                                                        row.getId()
                                                                                                .toString(),
                                                                                        actor),
                                                                        state.favorite(),
                                                                        state.lastVisitedAt());
                                                            })
                                                    .toList();
                                    return new PageResult<>(items, found.getTotal());
                                });
        principals.unchanged(identity);
        return result;
    }

    @Override
    public ReportDashboardPreferences.State get(String id, long actor) {
        return controlled(id, actor, value -> state(preferences.get(actor, value)));
    }

    @Override
    public ReportDashboardPreferences.State favorite(
            ReportDashboardPreferences.SetFavorite request, long actor) {
        if (request == null || request.favorite() == null) throw invalid("缺少收藏设置");
        return controlled(
                request.id(),
                actor,
                id -> {
                    preferences.favorite(actor, id, request.favorite());
                    return state(preferences.get(actor, id));
                });
    }

    @Override
    public ReportDashboardPreferences.State visit(
            ReportDashboardPreferences.Visit request, long actor) {
        if (request == null) throw invalid("缺少访问看板");
        return controlled(
                request.id(),
                actor,
                id -> {
                    preferences.visit(actor, id);
                    return state(preferences.get(actor, id));
                });
    }

    private ReportDashboardPreferences.State controlled(
            String id, long actor, LongFunction<ReportDashboardPreferences.State> action) {
        permission(actor);
        long value = validator.id(id, "仪表板");
        ReportPrincipals.Snapshot identity = principals.snapshot(actor);
        ReportDashboardPreferences.State result =
                new TransactionTemplate(manager)
                        .execute(
                                tx -> {
                                    datasets.designReadLock();
                                    ReportDashboardDO row = dashboards.lock(value, false);
                                    if (row == null
                                            || !ReportResourceStatusEnum.ACTIVE.matches(
                                                    row.getStatus())
                                            || row.getPublishedVersion() == null)
                                        throw new AccessDeniedException("仪表板不存在或无权访问");
                                    access.require(
                                            ReportResourceKindEnum.DASHBOARD,
                                            value,
                                            row.getOwnerId(),
                                            identity.principals(),
                                            ReportResourceActionEnum.VIEW);
                                    if (dashboards.version(value, row.getPublishedVersion())
                                            == null) throw invalid("仪表板发布版本不存在");
                                    return action.apply(value);
                                });
        principals.unchanged(identity);
        return result;
    }

    private ReportDashboardPreferences.State state(ReportDashboardPreferenceDO row) {
        return row == null
                ? new ReportDashboardPreferences.State(false, null)
                : new ReportDashboardPreferences.State(
                        Boolean.TRUE.equals(row.getFavorite()),
                        row.getLastVisitedAt() == null ? null : row.getLastVisitedAt().toString());
    }

    private void permission(long actor) {
        if (actor <= 0 || !permissions.hasAnyPermissions(actor, "nocode:report:query"))
            throw new AccessDeniedException("没有报表查看权限");
    }
}
