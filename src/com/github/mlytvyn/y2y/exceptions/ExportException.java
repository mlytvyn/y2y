
package com.github.mlytvyn.y2y.exceptions;

public class ExportException extends RuntimeException {

    public ExportException(String message) {
        super(message);
    }

    public ExportException(Throwable cause) {
        super(cause);
    }

    public ExportException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
