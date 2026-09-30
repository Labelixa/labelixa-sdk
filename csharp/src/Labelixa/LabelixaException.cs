using System;

namespace Labelixa
{
    /// <summary>
    /// Thrown when the API answers with anything other than 200.
    /// </summary>
    /// <remarks>
    /// The message carries the server's own text verbatim. Wrapping it in
    /// a friendlier sentence would hide the only words that say what is
    /// actually wrong.
    /// </remarks>
    public class LabelixaException : Exception
    {
        /// <summary>HTTP status code, or 0 when the client refused to send.</summary>
        public int Status { get; }

        /// <summary>The server's message, uninterpreted.</summary>
        public string ServerMessage { get; }

        /// <summary>Builds an exception from a status code and the server's message.</summary>
        public LabelixaException(int status, string serverMessage)
            : base(status == 0 ? serverMessage : "HTTP " + status + ": " + serverMessage)
        {
            Status = status;
            ServerMessage = serverMessage;
        }
    }
}
