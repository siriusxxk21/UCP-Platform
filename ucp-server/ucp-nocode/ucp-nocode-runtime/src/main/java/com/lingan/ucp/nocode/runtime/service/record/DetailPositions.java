package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.runtime.dal.mapper.DetailPositionMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.function.Function;

/** 旧数据无排序记录时保留原顺序，首次整单保存后使用用户提交的顺序。 */
@Service
public class DetailPositions {
    @Resource private DetailPositionMapper positions;

    public <T> List<T> order(
            String object, String detail, String parent, List<T> rows, Function<T, String> key) {
        if (parent == null || rows.size() < 2) return rows;
        var ids = positions.order(object, detail, parent);
        Map<String, Integer> ordering = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) ordering.put(ids.get(i), i);
        return rows.stream()
                .sorted(
                        Comparator.comparingInt(
                                row -> ordering.getOrDefault(key.apply(row), Integer.MAX_VALUE)))
                .toList();
    }

    public void save(String object, String detail, String parent, List<String> rows, long actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw invalid("明细顺序须随主单据保存");
        if (rows.size() > 500) throw invalid("明细超过 500 行");
        positions.clear(object, detail, parent, Long.toString(actor));
        if (!rows.isEmpty()) positions.append(object, detail, parent, rows, Long.toString(actor));
    }
}
