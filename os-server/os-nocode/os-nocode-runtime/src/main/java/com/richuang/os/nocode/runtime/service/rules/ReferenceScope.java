package com.richuang.os.nocode.runtime.service.rules;

import java.util.List;

public record ReferenceScope(
        String state,
        String message,
        List<String> pendingFields,
        com.richuang.os.common.dto.DynamicConditionDTO conditions,
        boolean hasRecordKey,
        List<String> recordKeys) {}
