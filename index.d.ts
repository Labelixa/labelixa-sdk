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

export interface ClientOptions {
  /** lbx_ API key; omit for anonymous (free, rate-limited) use. */
  apiKey?: string;
  baseUrl?: string;
  /** Test hook; normal use omits it. */
  fetch?: typeof fetch;
}

export interface RenderOptions {
  dpmm?: number;
  widthIn?: number;
  heightIn?: number;
}

export declare class Client {
  constructor(opts?: ClientOptions);
  renderPng(zpl: string, opts?: RenderOptions & { index?: number; rotation?: number }): Promise<Uint8Array>;
  /** index null (default) renders ALL labels; quota is charged per label. */
  renderPdf(zpl: string, opts?: RenderOptions & { index?: number | null }): Promise<Uint8Array>;
  validate(zpl: string, opts?: RenderOptions): Promise<Record<string, unknown>>;
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
