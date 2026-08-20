package com.mojang.logging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test stub for Mojang LogUtils — delegates to SLF4J.
 */
public final class LogUtils {
    public static Logger getLogger() {
        return LoggerFactory.getLogger("TestLogger");
    }
}
