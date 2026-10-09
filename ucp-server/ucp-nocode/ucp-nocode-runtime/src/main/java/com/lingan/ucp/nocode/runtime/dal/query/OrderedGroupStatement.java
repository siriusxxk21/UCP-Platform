package com.lingan.ucp.nocode.runtime.dal.query;

import java.util.List;

/** 分组列仅由已发布对象白名单解析；不接受客户端 SQL。 */
public record OrderedGroupStatement(
        RecordStatement record, List<String> columns, String outputColumn) {}
