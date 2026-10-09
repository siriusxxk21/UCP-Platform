package com.richuang.os.nocode.api;

import com.richuang.os.nocode.api.DataCenter.ImportColumn;

import java.util.List;

/** 对象定义导入的预检结果；不依赖 Excel 或 HTTP 文件类型。 */
public final class ObjectImports {
    private ObjectImports() {}

    /** 原文件中的行号与可展示错误。 */
    public record Error(int row, String message) {}

    /** 合法字段及逐行错误同时返回，保持原预检契约。 */
    public record Preview(List<ImportColumn> columns, List<Error> errors) {}
}
