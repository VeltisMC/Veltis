package org.veltismc.veltis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An error logger to properly throw exceptions with color
 */
public class ErrorLogger {
    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    public static <T extends Exception> T thrower(T e) {
        String callingClassName = e.getStackTrace()[0].getClassName();
        Logger log = LoggerFactory.getLogger(callingClassName);
        System.err.print(RED);
        log.error("Stack trace: ", e);
        System.err.print(RESET);
        return e;
    }
}