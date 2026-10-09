package com.richuang.os.nocode.report.service.dataset;

import com.richuang.os.nocode.api.ReportDatasets;

/** 数据集来源校验，不读写业务记录。调用方必须先校验结构发现/设计权限，运行时仍需逐次数据授权。 */
public interface ReportDatasetSourceService {
    /** 固定所有对象版本并解析关系和字段；失败不返回部分解析结果，不变更原对象。 */
    ReportDatasets.ResolvedSource resolve(ReportDatasets.Source source);
}
