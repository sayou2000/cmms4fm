import type { Config } from './config.js';
import { failure, type Failure } from './cmms/errors.js';
import { keyFingerprint } from './logger.js';

/**
 * Where the API key comes from (konzept §4.2). The server holds no identity of its own in
 * either mode; it only decides which key to forward.
 *
 * - **passthrough** (default): the client presents its own key per session — over HTTP as an
 *   `x-api-key` header or as `Authorization: Bearer <key>`, whichever the client can send; on
 *   stdio as `CMMS_API_KEY` in the environment. Rights, company scope and the CMMS's own audit
 *   trail then hang off the real user.
 * - **service**: the server forwards one key from its environment. Simplest to set up, and
 *   correct only when a single trusted agent is the sole client.
 */

export interface Caller {
  apiKey: string;
  /** Short hash of the key, for the audit log. The key itself is never logged. */
  fingerprint: string;
  source: 'x-api-key' | 'bearer' | 'environment' | 'service';
}

export type CallerResolution = { ok: true; caller: Caller } | { ok: false; failure: Failure };

/** Header maps arrive from the SDK as `Record<string, string | string[] | undefined>`. */
export type IncomingHeaders = Record<string, string | string[] | undefined> | undefined;

export function resolveCaller(config: Config, headers: IncomingHeaders): CallerResolution {
  if (config.authMode === 'service') {
    const apiKey = config.serviceApiKey;
    if (!apiKey) {
      return {
        ok: false,
        failure: failure('not_configured', 'AUTH_MODE=service but SERVICE_API_KEY is not set'),
      };
    }
    return { ok: true, caller: caller(apiKey, 'service') };
  }

  const fromApiKeyHeader = headerValue(headers, 'x-api-key');
  if (fromApiKeyHeader) {
    return { ok: true, caller: caller(fromApiKeyHeader, 'x-api-key') };
  }

  // `Authorization: Bearer <key>` carries the same key. Not a second credential scheme and
  // not OAuth — the token is forwarded to the CMMS as `x-api-key` exactly as an `x-api-key`
  // header would be, so it grants nothing extra.
  //
  // It is here because clients overwhelmingly offer a bearer field and often nothing else:
  // n8n's MCP Client node presents "Bearer Auth" as the ready-made option, and reaching a
  // custom header there means picking a differently-named credential type. Refusing the
  // header that every client can send buys no safety and costs an afternoon of "the
  // connection works but no tool does" — which is exactly how this was found.
  const fromBearer = headerValue(headers, 'authorization');
  if (fromBearer) {
    const match = /^Bearer\s+(.+)$/i.exec(fromBearer);
    const token = match?.[1]?.trim();
    if (token) return { ok: true, caller: caller(token, 'bearer') };
  }

  // The environment key is the stdio transport's *only* way to carry one, and deliberately
  // not a fallback over HTTP: there, an unauthenticated caller would silently act as this
  // key's user, which on a publicly routed /mcp is a hole rather than a convenience. One
  // shared key over HTTP is what AUTH_MODE=service is for, and it says so in the config.
  if (config.stdioApiKey && config.transport === 'stdio') {
    return { ok: true, caller: caller(config.stdioApiKey, 'environment') };
  }

  return {
    ok: false,
    failure: failure(
      'unauthenticated',
      config.transport === 'stdio'
        ? 'no API key: set CMMS_API_KEY in the environment of this MCP server'
        : 'no API key: send it on the MCP request as an "x-api-key" header or as "Authorization: Bearer <key>", or run the server with AUTH_MODE=service',
    ),
  };
}

function caller(apiKey: string, source: Caller['source']): Caller {
  return { apiKey, fingerprint: keyFingerprint(apiKey), source };
}

function headerValue(headers: IncomingHeaders, name: string): string | undefined {
  if (!headers) return undefined;
  // Node lowercases incoming header names; a client that sends `X-Api-Key` must still work.
  for (const [key, value] of Object.entries(headers)) {
    if (key.toLowerCase() !== name) continue;
    const raw = Array.isArray(value) ? value[0] : value;
    const trimmed = raw?.trim();
    if (trimmed) return trimmed;
  }
  return undefined;
}
