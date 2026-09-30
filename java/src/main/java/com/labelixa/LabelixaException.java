package com.labelixa;

/**
 * A failed call. {@link #getServerMessage()} is the server's own text,
 * verbatim: wrapping it in a friendlier sentence would hide the only
 * words that say what is wrong.
 *
 * <p>Unchecked on purpose. The one failure worth a dedicated catch is the
 * quota answer ({@link QuotaExceededException}); everything else is
 * reported, not handled, and a checked exception on every call would
 * only add {@code throws} clauses to the caller's code.
 *
 * <p>A transport failure (no connection, timeout) has status {@code 0}
 * and the underlying {@link java.io.IOException} as its cause.
 */
public class LabelixaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** HTTP status, or 0 when no answer arrived. */
    private final int status;
    /** The server's text, verbatim and capped. */
    private final String serverMessage;

    /**
     * Creates the exception.
     *
     * @param status HTTP status, or {@code 0} when the request never got an answer
     * @param serverMessage the server's message, or the transport error text
     */
    public LabelixaException(int status, String serverMessage) {
        this(status, serverMessage, null);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param status HTTP status, or {@code 0} when the request never got an answer
     * @param serverMessage the server's message, or the transport error text
     * @param cause the underlying failure, may be {@code null}
     */
    public LabelixaException(int status, String serverMessage, Throwable cause) {
        super(status == 0 ? serverMessage : "HTTP " + status + ": " + serverMessage, cause);
        this.status = status;
        this.serverMessage = serverMessage;
    }

    /**
     * Returns the HTTP status, or {@code 0} when no answer arrived.
     *
     * @return the status code
     */
    public int getStatus() {
        return status;
    }

    /**
     * Returns the server's message verbatim (capped at 500 characters).
     *
     * @return the message text
     */
    public String getServerMessage() {
        return serverMessage;
    }
}
