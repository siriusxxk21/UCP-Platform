package com.richuang.os.module.bpm.framework.flowable.core.query;

import com.richuang.os.module.bpm.dal.mapper.task.BpmHistoricTaskQueryMapper;

import org.flowable.common.engine.impl.interceptor.CommandContext;
import org.flowable.engine.ManagementService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.impl.util.CommandContextUtil;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.service.impl.HistoricTaskInstanceQueryImpl;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;

/**
 * 历史任务查询的最小扩展：在数据库计数与分页前排除自动发起人节点。
 *
 * <p>Flowable 7.2 的公开查询 API 没有节点不等于条件。筛选与分页复用引擎 SQL 片段， 当页任务及变量继续由引擎查询加载，不另建引擎实体映射。
 */
public class BpmHistoricTaskInstanceQuery extends HistoricTaskInstanceQueryImpl {

    public static final String MAPPER_RESOURCE = "flowable/BpmHistoricTaskQueryMapper.xml";

    private final String excludedTaskDefinitionKey;

    private BpmHistoricTaskInstanceQuery(
            ProcessEngineConfigurationImpl configuration, String excludedTaskDefinitionKey) {
        super(
                configuration.getCommandExecutor(),
                configuration.getDatabaseType(),
                configuration.getTaskServiceConfiguration(),
                configuration.getVariableServiceConfiguration());
        this.excludedTaskDefinitionKey = excludedTaskDefinitionKey;
    }

    /** 取得当前引擎的命令、变量类型和事务配置，不创建第二套引擎或数据源。 */
    public static BpmHistoricTaskInstanceQuery create(
            ManagementService managementService, String excludedTaskDefinitionKey) {
        return managementService.executeCommand(
                context ->
                        new BpmHistoricTaskInstanceQuery(
                                CommandContextUtil.getProcessEngineConfiguration(context),
                                excludedTaskDefinitionKey));
    }

    public String getExcludedTaskDefinitionKey() {
        return excludedTaskDefinitionKey;
    }

    @Override
    public long executeCount(CommandContext commandContext) {
        prepareQuery();
        return mapper(commandContext).count(this);
    }

    @Override
    public List<HistoricTaskInstance> executeList(CommandContext commandContext) {
        prepareQuery();
        List<String> ids = mapper(commandContext).selectTaskIds(this);
        if (ids.isEmpty()) {
            return List.of();
        }
        // 仅加载数据库已选中的当页 ID；变量联表不能改变任务的分页边界。
        var query =
                CommandContextUtil.getProcessEngineConfiguration(commandContext)
                        .getHistoryService()
                        .createHistoricTaskInstanceQuery()
                        .taskIds(ids);
        if (includeTaskLocalVariables) query.includeTaskLocalVariables();
        if (includeProcessVariables) query.includeProcessVariables();
        if (includeCaseVariables) query.includeCaseVariables();
        if (includeIdentityLinks) query.includeIdentityLinks();
        var positions = new HashMap<String, Integer>();
        for (int index = 0; index < ids.size(); index++) {
            positions.put(ids.get(index), index);
        }
        // 按数据库已确定的顺序排列实际加载的实体，并发清理历史时不生成 null 任务。
        return query.list().stream()
                .sorted(Comparator.comparingInt(task -> positions.get(task.getId())))
                .toList();
    }

    private void prepareQuery() {
        ensureVariablesInitialized();
        if (taskServiceConfiguration.getHistoricTaskQueryInterceptor() != null) {
            taskServiceConfiguration
                    .getHistoricTaskQueryInterceptor()
                    .beforeHistoricTaskQueryExecute(this);
        }
    }

    private BpmHistoricTaskQueryMapper mapper(CommandContext context) {
        return CommandContextUtil.getDbSqlSession(context)
                .getSqlSession()
                .getMapper(BpmHistoricTaskQueryMapper.class);
    }
}
