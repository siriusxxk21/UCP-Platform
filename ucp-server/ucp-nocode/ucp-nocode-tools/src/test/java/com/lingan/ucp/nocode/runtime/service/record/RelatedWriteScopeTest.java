package com.lingan.ucp.nocode.runtime.service.record;

import static org.junit.jupiter.api.Assertions.*;

import com.lingan.ucp.nocode.api.ApplicationRecords;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

class RelatedWriteScopeTest {
    private ApplicationRecords.Save command() {
        return new ApplicationRecords.Save(
                "1", "2", null, null, Map.of(), Map.of(), Map.of(), null, "form");
    }

    @Test
    void onlyExactCommandCanUseGeneratedFields() {
        var first = command();
        var equalButDifferent = command();
        assertTrue(RelatedWriteScope.fields(first).isEmpty());
        RelatedWriteScope.execute(
                first,
                Set.of("generated"),
                () -> {
                    assertEquals(Set.of("generated"), RelatedWriteScope.fields(first));
                    assertTrue(RelatedWriteScope.fields(equalButDifferent).isEmpty());
                    return null;
                });
        assertTrue(RelatedWriteScope.fields(first).isEmpty());
    }

    @Test
    void failureAndNestedCallsRestoreScope() {
        var first = command();
        var nested = command();
        assertThrows(
                IllegalStateException.class,
                () ->
                        RelatedWriteScope.execute(
                                first,
                                Set.of("first"),
                                () -> {
                                    RelatedWriteScope.execute(
                                            nested,
                                            Set.of("nested"),
                                            () -> {
                                                assertTrue(
                                                        RelatedWriteScope.fields(first).isEmpty());
                                                assertEquals(
                                                        Set.of("nested"),
                                                        RelatedWriteScope.fields(nested));
                                                return null;
                                            });
                                    assertEquals(Set.of("first"), RelatedWriteScope.fields(first));
                                    throw new IllegalStateException("rollback");
                                }));
        assertTrue(RelatedWriteScope.fields(first).isEmpty());
        assertTrue(RelatedWriteScope.fields(nested).isEmpty());
    }
}
