package com.richuang.os.nocode.metadata.service.object;

import com.richuang.os.nocode.metadata.dal.mapper.ObjectOperationDataMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/** 计数失败返回未知；保存点避免单表权限或漂移错误破坏整个只读诊断事务。 */
@Component
public class ObjectOperationDataQueries {
    @Resource private ObjectOperationDataMapper mapper;
    @Resource private PlatformTransactionManager manager;
    private TransactionTemplate tx;

    @PostConstruct
    void initialize() {
        tx = new TransactionTemplate(manager);
        tx.setReadOnly(true);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    Long rows(String schema, String table) {
        return read(() -> mapper.countRows(new ObjectOperationDataMapper.Table(schema, table)));
    }

    ObjectOperationDataMapper.Counts column(String schema, String table, String column) {
        return read(
                () ->
                        mapper.countColumn(
                                new ObjectOperationDataMapper.Column(schema, table, column)));
    }

    Long duplicates(String schema, String table, String column) {
        return read(
                () ->
                        mapper.countDuplicates(
                                new ObjectOperationDataMapper.Column(schema, table, column)));
    }

    <T> T read(Supplier<T> query) {
        try {
            return tx.execute(status -> query.get());
        } catch (DataAccessException exception) {
            return null;
        }
    }
}
