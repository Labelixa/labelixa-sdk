export declare class LabelixaError extends Error {
  status: number;
  /** The server's own error text, verbatim. */
  serverMessage: string;
  constructor(status: number, serverMessage: string);
}

export declare class QuotaExceeded extends LabelixaError {
  /** Seconds to wait before retrying (from Retry-After). */
  retryAfter: number;
  /** Server's suggestion when present: "upgrade" | "addon" | null. */
  action: string | null;
  constructor(status: number, serverMessage: string, retryAfter: number,
              action: string | null);
}

export type Language = "zpl" | "epl" | "tspl" | "cpcl";
/** Printer languages the API renders and lints; ZPL is the default. */
export declare const LANGUAGES: readonly Language[];

export interface ClientOptions {
  /** lbx_ API key; omit for anonymous (free, rate-limited) use. */
  apiKey?: string;
  baseUrl?: string;
  /** Sent as the `X-Client` header (e.g. "cli/0.3.0"); attributes usage
   *  to an integration, never to a person. */
  clientName?: string;
  /** Test hook; normal use omits it. */
  fetch?: typeof fetch;
}

export interface RenderOptions {
  /** "zpl" (default) | "epl" | "tspl" | "cpcl". For the last three the
   *  label size comes from the code; dpmm/width/height are ignored. */
  language?: Language;
  dpmm?: number;
  widthIn?: number;
  heightIn?: number;
}

export declare class Client {
  constructor(opts?: ClientOptions);
  renderPng(code: string, opts?: RenderOptions & { index?: number; rotation?: number }): Promise<Uint8Array>;
  /** index null (default) renders ALL labels; quota is charged per label. */
  renderPdf(zpl: string, opts?: RenderOptions & { index?: number | null }): Promise<Uint8Array>;
  validate(code: string, opts?: RenderOptions): Promise<Record<string, unknown>>;
  toEpl(zpl: string, opts?: RenderOptions): Promise<string>;
  /** svg returns a string, png returns bytes. Invalid input throws with
   *  the server's X-Warnings text (the API returns 200 + error image). */
  barcode(data: string, opts?: { type?: string; format?: "svg" | "png" }): Promise<string | Uint8Array>;
  /** Heuristic; result carries a confidence tier, not a probability. */
  languageDetect(code: string): Promise<Record<string, unknown>>;
  /** Risk analysis against a printer model slug; not an emulator. */
  compatibility(zpl: string, model: string): Promise<Record<string, unknown>>;
}

export default Client;
