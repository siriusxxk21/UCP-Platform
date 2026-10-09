package com.richuang.os.module.system.service.dept;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 部门编码生成规则单元测试。
 */
class DeptServiceImplTest {

    @Test
    void nextDeptCodeShouldStartAtOneAndIncrease() {
        assertEquals("ORG-001", DeptServiceImpl.nextDeptCode("ORG", List.of()));
        assertEquals("ORG-003", DeptServiceImpl.nextDeptCode("ORG", List.of("ORG-001", "ORG-002")));
    }

    @Test
    void nextDeptCodeShouldIgnoreOtherOrganizationsAndInvalidCodes() {
        assertEquals("ORG-001", DeptServiceImpl.nextDeptCode("ORG",
                List.of("OTHER-999", "ORG-X01", "ORG-7")));
    }

    @Test
    void nextDeptCodeShouldNaturallyExpandPast999() {
        assertEquals("ORG-1000", DeptServiceImpl.nextDeptCode("ORG", List.of("ORG-999")));
    }

    @Test
    void nextDeptCodeShouldCalculateEachOrganizationIndependently() {
        List<String> historicalCodes = List.of("A-009", "B-015");
        assertEquals("A-010", DeptServiceImpl.nextDeptCode("A", historicalCodes));
        assertEquals("B-016", DeptServiceImpl.nextDeptCode("B", historicalCodes));
    }
}
