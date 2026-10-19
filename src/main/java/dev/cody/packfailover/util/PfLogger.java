package dev.cody.packfailover.util;

import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Small logging facade that keeps debug logging behind a live configuration flag.
 */
public final class PfLogger {

    private static final String PREFIX = "";

    private final Logger logger;
    private final BooleanSupplier debug;

    public PfLogger(Logger logger, BooleanSupplier debug) {
        this.logger = logger;
        this.debug = debug;
    }

    public void info(String message) {
        logger.info(PREFIX + message);
    }

    public void warn(String message) {
        logger.warning(PREFIX + message);
    }

    public void warn(String message, Throwable throwable) {
        logger.log(Level.WARNING, PREFIX + message, throwable);
    }

    public void debug(String message) {
        if (debug.getAsBoolean()) {
            logger.info(PREFIX + "[debug] " + message);
        }
    }

    public void debug(String message, Throwable throwable) {
        if (debug.getAsBoolean()) {
            logger.log(Level.INFO, PREFIX + "[debug] " + message, throwable);
        }
    }
}
