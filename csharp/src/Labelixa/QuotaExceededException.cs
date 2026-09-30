namespace Labelixa
{
    /// <summary>
    /// Thrown for 402 and 429.
    /// </summary>
    /// <remarks>
    /// A separate type on purpose: a caller that cannot tell a quota
    /// response from a generic failure either never retries or retries
    /// immediately, and both are wrong. <see cref="RetryAfter"/> is the
    /// server's Retry-After in seconds; <see cref="Action"/> is its hint
    /// ("upgrade", "addon") when it sends one.
    /// </remarks>
    public sealed class QuotaExceededException : LabelixaException
    {
        /// <summary>Seconds to wait before retrying, from Retry-After.</summary>
        public int RetryAfter { get; }

        /// <summary>The server's action hint, or null when it sends none.</summary>
        public string? Action { get; }

        /// <summary>Builds a quota exception from the server's response.</summary>
        public QuotaExceededException(int status, string serverMessage,
            int retryAfter, string? action)
            : base(status, serverMessage)
        {
            RetryAfter = retryAfter;
            Action = action;
        }
    }
}
