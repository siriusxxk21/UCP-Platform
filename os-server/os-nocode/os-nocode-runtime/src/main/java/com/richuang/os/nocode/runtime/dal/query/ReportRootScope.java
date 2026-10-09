package com.richuang.os.nocode.runtime.dal.query;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 仅由服务端已授权报表 SQL 编译的根记录范围；HTTP 请求不能提交 SQL 或参数路径。 */
public record ReportRootScope(String sql, List<Object> values) {
    public ReportRootScope {
        // 下钻空值原键是合法绑定值，不能使用会拒绝 null 的 List.copyOf。
        values = Collections.unmodifiableList(new ArrayList<>(values));
    }
}
