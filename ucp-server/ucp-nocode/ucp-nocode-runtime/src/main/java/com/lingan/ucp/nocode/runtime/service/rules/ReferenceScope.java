package com.lingan.ucp.nocode.runtime.service.rules;

import java.util.List;

public record ReferenceScope(
        String state,
        String message,
        List<String> pendingFields,
        com.lingan.ucp.common.dto.DynamicConditionDTO conditions,
        boolean hasRecordKey,
        List<String> recordKeys) {}
