package com.richuang.os.nocode.runtime.service.live;

/** 事务提交后被调用；实现必须很快返回，不得抛出。 */
public interface RecordChangeListener {
    void committed(RecordChangeBatch batch);
}
