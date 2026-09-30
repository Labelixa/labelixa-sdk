/**
 * Thin Java client for the Labelixa REST API: render, validate and convert
 * thermal printer label code (ZPL, EPL, TSPL, CPCL) without a printer.
 *
 * <pre>{@code
 * LabelixaClient client = new LabelixaClient();                       // anonymous
 * LabelixaClient client = LabelixaClient.builder().apiKey("lbx_...").build();
 *
 * byte[] png = client.renderPng(zpl);
 * Map<String, Object> report = client.validate(zpl);
 * }</pre>
 *
 * <p>Design notes, shared with the Python, Node, Go, PHP and C# clients:
 *
 * <ul>
 *   <li>THIN wrapper. Every method maps 1:1 to a documented REST endpoint.
 *       No client-side magic and no hidden retries: a retry that swallows
 *       a quota response turns a clear signal into a slow mystery.
 *       <a href="https://labelixa.com/docs/api">https://labelixa.com/docs/api</a>
 *       is the source of truth.</li>
 *   <li>Errors carry the server's own message. Quota exhaustion is a
 *       distinct type ({@link com.labelixa.QuotaExceededException}) with
 *       the retry delay and the server's action hint, because a caller
 *       that cannot tell 429 from a generic failure either never retries
 *       or retries immediately, and both are wrong.</li>
 *   <li>JDK only. A label client is not worth a dependency tree in
 *       someone else's build; the JDK has no JSON API, so a small parser
 *       ships inside the package.</li>
 * </ul>
 */
package com.labelixa;
