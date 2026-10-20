using System.Text.Json;

namespace Labelixa
{
    /// <summary>
    /// WIRE VOCABULARY: the API's own field names that this client reads by
    /// name, in one file.
    /// </summary>
    /// <remarks>
    /// Some fields of the Labelixa API are named in Turkish. They are
    /// PROTOCOL: renaming them here would not translate anything, it would
    /// simply stop reading the server's answer. So they are not translated,
    /// and they are not hidden either. They live in this single file so the
    /// publish guard can exempt exactly one file from the "no Turkish words"
    /// rule instead of losing that rule across the whole package. The guard
    /// still checks this file for ticket ids, non-English characters and
    /// audit markers. Reference: https://labelixa.com/docs/api
    /// </remarks>
    public static class Contract
    {
        /// <summary>
        /// The field of an error answer (any non-2xx) that carries the
        /// server's sentence: <c>{"hata": "..."}</c>.
        /// </summary>
        public const string ErrorField = "hata";

        /// <summary>
        /// The sentence of an API error answer, or the body as it came when
        /// it has any other shape. Capped at 500 characters either way.
        /// </summary>
        public static string ServerText(string raw)
        {
            var text = raw;
            try
            {
                using var document = JsonDocument.Parse(raw);
                if (document.RootElement.ValueKind == JsonValueKind.Object
                    && document.RootElement.TryGetProperty(ErrorField, out var field)
                    && field.ValueKind == JsonValueKind.String)
                {
                    text = field.GetString() ?? raw;
                }
            }
            catch (JsonException)
            {
                // Not JSON: keep the raw text.
            }

            return text.Length > 500 ? text.Substring(0, 500) : text;
        }
    }
}
