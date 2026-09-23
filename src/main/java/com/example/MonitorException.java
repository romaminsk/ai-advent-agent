package com.example;

/** Safe user-facing monitor error without raw process diagnostics. */
public final class MonitorException extends RuntimeException {
    public MonitorException(String message) {
        super(message);
    }

    public MonitorException(String message, Throwable cause) {
        super(message, cause);
    }
}
