package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.TaskCenter.Period;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskScheduling;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

/** 日期区间及粗粒度计划边界；不启动服务或访问数据库。 */
class TaskSchedulingRulesTest {
    @Test
    void weekNormalizesAcrossTheYearBoundary() {
        TaskScheduling.Range result =
                TaskScheduling.range(Period.WEEK, LocalDate.of(2031, 1, 1), null);
        assertThat(result.date()).isEqualTo(LocalDate.of(2030, 12, 30));
        assertThat(result.endDate()).isEqualTo(LocalDate.of(2031, 1, 5));
    }

    @Test
    void monthIncludesLeapDay() {
        assertThat(TaskScheduling.range(Period.MONTH, LocalDate.of(2032, 2, 13), null).endDate())
                .isEqualTo(LocalDate.of(2032, 2, 29));
    }

    @Test
    void concreteRangePreservesInclusiveDatesAcrossMonths() {
        TaskScheduling.Range result =
                TaskScheduling.range(
                        Period.DAY, LocalDate.of(2031, 3, 30), LocalDate.of(2031, 4, 2));
        assertThat(result.date()).isEqualTo(LocalDate.of(2031, 3, 30));
        assertThat(result.endDate()).isEqualTo(LocalDate.of(2031, 4, 2));
    }

    @Test
    void reversedDatesAndPretendPartialWeeksAreRejected() {
        LocalDate date = LocalDate.of(2031, 3, 12);
        assertThatThrownBy(() -> TaskScheduling.range(Period.DAY, date, date.minusDays(1)))
                .hasMessageContaining("不能早于");
        assertThatThrownBy(() -> TaskScheduling.range(Period.WEEK, date, date))
                .hasMessageContaining("完整期间");
        assertThatThrownBy(() -> TaskScheduling.range(null, date, null))
                .hasMessageContaining("请选择");
    }
}
