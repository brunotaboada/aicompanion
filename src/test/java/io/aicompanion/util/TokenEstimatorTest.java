package io.aicompanion.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TokenEstimatorTest {

    @Test
    void nullAndEmptyReturnZero() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(""));
    }

    @Test
    void shortStringsRoundUp() {
        // 1-3 chars all map to 1 token; 4 chars -> 1; 5 chars -> 2
        assertEquals(1, TokenEstimator.estimate("a"));
        assertEquals(1, TokenEstimator.estimate("abc"));
        assertEquals(1, TokenEstimator.estimate("abcd"));
        assertEquals(2, TokenEstimator.estimate("abcde"));
    }

    @Test
    void scalesLinearlyWithLength() {
        String s = "x".repeat(4000);
        assertEquals(1000, TokenEstimator.estimate(s));
    }

    @Test
    void monotonicallyIncreasing() {
        assertTrue(TokenEstimator.estimate("hello world") >= TokenEstimator.estimate("hello"));
    }
}
