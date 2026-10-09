package com.richuang.os.nocode.api;

import com.richuang.os.framework.common.exception.ServiceException;

/** 无代码领域业务码；异常类型及 HTTP 响应处理统一复用底座。 */
public final class NocodeErrorCodes {

    public static final int INVALID = 1_050_000_001;
    public static final int NOT_FOUND = 1_050_000_002;
    public static final int DUPLICATE = 1_050_000_003;
    public static final int CONFLICT = 1_050_000_004;
    public static final int FOREIGN_FIELD = 1_050_000_005;
    public static final int UNSUPPORTED_STATE = 1_050_000_006;
    public static final int VERSION_CHANGED = 1_050_000_007;

    private NocodeErrorCodes() {}

    /** 带具体字段位置的校验错误，交给底座全局异常处理器输出。 */
    public static ServiceException invalid(String message) {
        return new ServiceException(INVALID, message);
    }

    /** 正式运行入口的发布版本已变化，客户端须刷新完整配置后再查询。 */
    public static ServiceException versionChanged(String message) {
        return new ServiceException(VERSION_CHANGED, message);
    }
}
