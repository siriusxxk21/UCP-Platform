package com.richuang.os.nocode.runtime.service.maintenance;

import com.richuang.os.nocode.api.OrderedCalculationCalibration.*;
import com.richuang.os.nocode.api.OrderedCalculations;

import java.util.List;

/** 有序落库校准的管理入口，每组独立事务，服务端保留可重试位置。 */
public interface OrderedCalibrationService {
    List<OrderedCalculations.State> status(String objectId, long actor);

    Preview preview(PreviewRequest command, long actor);

    Result calibrate(Command command, long actor);

    Result resume(Command command, long actor);

    Result pause(Command command, long actor);
}
