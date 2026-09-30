package com.labelixa;

/**
 * The server answered 402 or 429: the quota is used up or the rate limit
 * hit. {@link #getRetryAfter()} is the server's {@code Retry-After} in
 * seconds; {@link #getAction()} is its hint ({@code "upgrade"},
 * {@code "addon"}) when it sends one.
 *
 * <p>Its own type so a caller can back off for exactly the delay the
 * server asked for instead of guessing, or retrying immediately.
 */
public final class QuotaExceededException extends LabelixaException {

    private static final long serialVersionUID = 1L;

    /** Seconds to wait, from Retry-After (60 when absent). */
    private final int retryAfter;
    /** The server's X-Quota-Action hint, or empty. */
    private final String action;

    /**
     * Creates the exception.
     *
     * @param status 402 or 429
     * @param serverMessage the server's message
     * @param retryAfter seconds to wait before trying again
     * @param action the server's action hint, empty when none was sent
     */
    public QuotaExceededException(int status, String serverMessage,
                                  int retryAfter, String action) {
        super(status, serverMessage);
        this.retryAfter = retryAfter;
        this.action = action == null ? "" : action;
    }

    /**
     * Returns the seconds to wait before the next attempt (60 when the
     * server sent no {@code Retry-After}).
     *
     * @return the delay in seconds
     */
    public int getRetryAfter() {
        return retryAfter;
    }

    /**
     * Returns the server's action hint, for example {@code "upgrade"} or
     * {@code "addon"}, or an empty string.
     *
     * @return the hint
     */
    public String getAction() {
        return action;
    }
}
