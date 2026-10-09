package com.richuang.os.nocode.api.work;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.enums.WorkSourceEnum;

/** 服务端来源范围，不是授权凭证；HTTP 不接收此对象，调用前须验证真实任务资格。 */
public record WorkSourceRef(WorkSourceEnum type, String id) {
    public static final WorkSourceRef PERSONAL =
            new WorkSourceRef(WorkSourceEnum.BUSINESS_FORM, null);

    public WorkSourceRef {
        if (type == null
                || (type == WorkSourceEnum.BUSINESS_FORM
                        ? id != null
                        : id == null || id.isBlank() || id.length() > 128)) throw invalid("工作来源无效");
    }

    public String sourceId(String draftId) {
        return type == WorkSourceEnum.BUSINESS_FORM ? draftId : id;
    }
}
