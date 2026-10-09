package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** 统一业务记录事务及约束异常转换，保留原事务传播规则。 */
@Component
public class RecordTransactions {
    @Resource private PlatformTransactionManager manager;

    @Resource
    private com.richuang.os.nocode.application.service.resource.ApplicationAutomationCatalog
            automations;

    private TransactionTemplate transaction;

    @PostConstruct
    void initialize() {
        transaction = new TransactionTemplate(manager);
    }

    <T> T tx(Supplier<T> action) {
        try {
            return transaction.execute(
                    s -> {
                        automations.lock(false);
                        return action.get();
                    });
        } catch (DataIntegrityViolationException e) {
            throw invalid("记录违反必填、唯一或引用约束，请检查输入");
        }
    }
}
