package io.aicompanion.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlatformTest {

    @Test
    void detectsWindowsFromOsName() {
        assertTrue(Platform.isWindows("Windows 11"));
        assertTrue(Platform.isWindows("windows server 2022"));
        assertFalse(Platform.isWindows("Mac OS X"));
        assertFalse(Platform.isWindows("Linux"));
    }

    @Test
    void detectsMacFromOsName() {
        assertTrue(Platform.isMac("Mac OS X"));
        assertTrue(Platform.isMac("macOS"));
        assertFalse(Platform.isMac("Windows 11"));
        assertFalse(Platform.isMac("Linux"));
    }

    @Test
    void detectsLinuxFromOsName() {
        assertTrue(Platform.isLinux("Linux"));
        assertTrue(Platform.isLinux("GNU/Linux"));
        assertFalse(Platform.isLinux("Mac OS X"));
        assertFalse(Platform.isLinux("Windows 11"));
    }

    @Test
    void currentOsMatchesAtMostOneDetector() {
        int matches = (Platform.isWindows() ? 1 : 0)
                    + (Platform.isMac()     ? 1 : 0)
                    + (Platform.isLinux()   ? 1 : 0);
        assertTrue(matches <= 1,
            "isWindows/isMac/isLinux must be mutually exclusive on any real OS");
    }
}
