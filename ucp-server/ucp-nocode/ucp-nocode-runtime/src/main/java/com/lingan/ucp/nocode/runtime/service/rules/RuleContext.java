package com.lingan.ucp.nocode.runtime.service.rules;

import com.lingan.ucp.nocode.api.DataCenter;

import java.util.Map;

/**
 * 规则求值上下文。recordId 是当前记录的 ID（新建未保存为 null）：只给数据联动的「等于当前记录」条件（CURRENT_RECORD）用， 没有 ID 时该条件处于待定状态、不查库。
 */
public record RuleContext(
        String applicationId,
        DataCenter.Definition definition,
        long actor,
        Map<String, DataCenter.Definition> preview,
        String recordId) {}
