package dev.cody.packfailover.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PoConfigTest {
    @Test void diagnosticsDisabledByDefault() {
        assertFalse(PoConfig.defaults().poc().enabled());
        assertTrue(new PoConfig.Poc(true, "http://127.0.0.1:1/{sha1}.zip").enabled());
    }
    @Test void retryBudgetClampsToZeroOrOne() {
        assertEquals(0, PoConfig.Fallback.normalizeMaxAttempts(-10));
        assertEquals(0, PoConfig.Fallback.normalizeMaxAttempts(0));
        assertEquals(1, PoConfig.Fallback.normalizeMaxAttempts(1));
        assertEquals(1, PoConfig.Fallback.normalizeMaxAttempts(5));
        assertThrows(IllegalArgumentException.class, () -> new PoConfig.Fallback(true, false, 2, true, true));
    }
}
