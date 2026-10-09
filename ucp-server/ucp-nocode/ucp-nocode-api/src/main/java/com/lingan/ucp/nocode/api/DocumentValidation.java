package com.lingan.ucp.nocode.api;

import com.lingan.ucp.framework.common.exception.ServiceException;

import java.util.List;
import java.util.Map;

/** 整单问题通过底座统一错误响应返回，只携带授权范围内的定位和提示。 */
public final class DocumentValidation {
    private DocumentValidation() {}

    public static ServiceException error(List<DocumentPolicy.Problem> problems) {
        return new ServiceException(
                        NocodeErrorCodes.INVALID,
                        problems.isEmpty() ? "整单校验失败" : problems.getFirst().message())
                .setDetails(Map.of("problems", List.copyOf(problems)));
    }
}
