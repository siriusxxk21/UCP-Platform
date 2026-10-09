package com.richuang.os.nocode.work.service.event;

import com.richuang.os.nocode.api.work.WorkSourceRef;
import com.richuang.os.nocode.enums.WorkEventTypeEnum;
import com.richuang.os.nocode.work.dal.dataobject.event.WorkEventDO;
import com.richuang.os.nocode.work.dal.mapper.event.WorkEventMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/** 提交事件在调用方事务中持久化；无活动事务时拒绝写入，避免材料回滚后遗留独立事件。 */
@Service
public class WorkEventServiceImpl implements WorkEventService {
    @Resource private WorkEventMapper mapper;

    @Override
    public void submitted(WorkSourceRef source, String submissionId, long actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("工作事件必须与提交共用事务");
        var row = new WorkEventDO();
        row.setId(UUID.randomUUID().toString());
        row.setEventType(WorkEventTypeEnum.SUBMITTED.getCode());
        row.setSourceType(source.type().getCode());
        row.setSourceId(source.id());
        row.setSubmissionId(submissionId);
        mapper.append(row, Long.toString(actor));
    }
}
