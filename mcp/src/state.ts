import type { Catalog } from './tools/registry.js';
import type { OpenApiDocument } from './openapi/types.js';

/**
 * What the server knows so far.
 *
 * The tool catalogue is derived from a document that lives in another container, so there is
 * a window at boot where the server is running and knows nothing yet. Earlier this was
 * handled by refusing to start until the document arrived, with a 150-second budget and
 * `process.exit(1)` after it — which turned a slow neighbour into a crash loop and, on a
 * cold machine, into `Restart limit reached`:
 *
 *   mcp waits for api with `service_started`, satisfied the moment the api *container*
 *   starts. The api then needs upwards of 150 seconds for Liquibase, Hibernate and Quartz.
 *   Both clocks start together, so on a full restart the budget expires before the api can
 *   possibly answer — every time, and each restart re-runs the same losing race.
 *
 * So "not ready" is now a state the server *has*, not a reason to die in. It listens
 * immediately, says what it is waiting for, and keeps trying for as long as it takes.
 */
export interface ServerState {
  /** Present once the document has been read and the catalogue built. */
  catalog?: Catalog;
  document?: OpenApiDocument;
  /** How many times loading has been attempted, successful ones included. */
  attempts: number;
  /** Why the last attempt failed, when one has. */
  lastError?: string;
  startedAt: Date;
  readyAt?: Date;
  /** Where the document came from, once it arrived. */
  origin?: string;
}

export function newState(): ServerState {
  return { attempts: 0, startedAt: new Date() };
}

export function isReady(state: ServerState): boolean {
  return state.catalog !== undefined;
}

/** The human-readable form used by /healthz and by the failure a tool call returns. */
export function describeState(state: ServerState): Record<string, unknown> {
  if (state.catalog) {
    return {
      status: 'ok',
      readyAt: state.readyAt?.toISOString(),
      origin: state.origin,
      profile: state.catalog.profile.name,
      tools: state.catalog.visible.length,
      hiddenTools: state.catalog.hidden.length,
      api: state.catalog.document,
    };
  }
  return {
    status: 'starting',
    waitingFor: 'the OpenAPI document from the CMMS',
    attempts: state.attempts,
    waitingSeconds: Math.round((Date.now() - state.startedAt.getTime()) / 1000),
    ...(state.lastError ? { lastError: state.lastError } : {}),
  };
}
