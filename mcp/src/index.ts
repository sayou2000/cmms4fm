import type { Server } from '@modelcontextprotocol/sdk/server/index.js';

import { loadConfig, type Config } from './config.js';
import { createLogger, type Logger } from './logger.js';
import { loadSpec } from './openapi/loader.js';
import { resolveProfile } from './tools/profiles.js';
import { RateLimiter } from './ratelimit.js';
import type { ServerContext } from './server.js';
import { newState } from './state.js';
import { buildCatalog } from './tools/registry.js';
import { startHttpTransport } from './transport/http.js';
import { startStdioTransport } from './transport/stdio.js';

/**
 * Boot in two independent steps: serve first, learn second.
 *
 * The order used to be the other way round — load the OpenAPI document, then start — and
 * that is what produced `Restart limit reached` on the deployed stack. The loader gave up
 * after 150 seconds and the process exited; Coolify restarted it into exactly the same race
 * it had just lost, because `mcp` waits on the api with `service_started` (the api's own
 * healthcheck is never green, see CLAUDE.md) and the api needs upwards of 150 seconds to
 * finish Liquibase, Hibernate and Quartz. On a cold machine both clocks start together, so
 * the budget could not be won. Every deploy against an already-running api worked, which is
 * precisely why it survived every test until the machine was restarted.
 *
 * The lesson is not "make the budget bigger". It is that a dependency being slow must not be
 * fatal: the server now listens straight away, answers what it is waiting for, and keeps
 * trying until the document arrives — for hours if that is what it takes.
 */
async function main(): Promise<void> {
  const config = loadConfig();
  const logger = createLogger(config);

  const context: ServerContext = {
    config,
    logger,
    state: newState(),
    rateLimiter: new RateLimiter({
      perMinute: config.rateLimitPerMinute,
      burst: config.rateLimitBurst,
    }),
  };

  // Checked before anything is served, because a profile that does not exist is a typo in
  // the deployment, not a slow neighbour. Doing it here rather than deep inside the
  // catalogue build is the difference between "misconfigured, here is the value and the
  // valid list" and a server that waits for a document it already has.
  try {
    resolveProfile(config.profile);
  } catch (error) {
    context.state.configError = error instanceof Error ? error.message : String(error);
    logger.error('this deployment cannot serve anything until its configuration is fixed', {
      problem: context.state.configError,
    });
  }

  let stdioServer: Server | undefined;

  if (config.transport === 'stdio') {
    stdioServer = await startStdioTransport(context);
  } else {
    const handle = await startHttpTransport(context);
    const shutdown = (signal: string) => {
      logger.info('shutting down', { signal });
      void handle.close().then(() => process.exit(0));
    };
    process.on('SIGTERM', () => shutdown('SIGTERM'));
    process.on('SIGINT', () => shutdown('SIGINT'));
  }

  // Not awaited: the transports above are already serving, and this runs for as long as the
  // process does. Skipped entirely when the configuration is broken — retrying a typo is
  // just noise, and /healthz already says what is wrong.
  if (!context.state.configError) void keepCatalogueCurrent(context, stdioServer);
}

/**
 * Loads the document, builds the catalogue, and then either stops (the default) or keeps
 * re-reading it every `SPEC_REFRESH_MINUTES` so an endpoint added upstream becomes a tool
 * without a redeploy.
 *
 * Failure is never fatal and never gives up. Before the first success it retries with a
 * backoff that stops growing at a minute, so a CMMS that takes ten minutes to come up costs
 * ten minutes of waiting rather than a crash loop. After a success a failed refresh keeps
 * the catalogue that already works.
 */
async function keepCatalogueCurrent(context: ServerContext, stdioServer?: Server): Promise<void> {
  const { config, logger } = context;

  for (;;) {
    const wasReady = context.state.catalog !== undefined;
    context.state.attempts += 1;

    try {
      const spec = await loadSpec(config, logger);
      // Two failure classes live in this block and they need different answers: fetching the
      // document can fail because the CMMS is not up yet, which waiting fixes; building the
      // catalogue from it fails only because of how this service is configured, which waiting
      // never fixes. Conflating them once had the server retry `PROFILE=FULL` for five
      // minutes while reporting that it was waiting for a document it had already read.
      let catalog;
      try {
        catalog = buildCatalog(spec.document, config, logger);
      } catch (error) {
        context.state.configError = error instanceof Error ? error.message : String(error);
        logger.error('the OpenAPI document loaded, but this deployment cannot use it', {
          problem: context.state.configError,
        });
        return;
      }
      const before = context.state.catalog?.visible.map((tool) => tool.name).join(',');
      const after = catalog.visible.map((tool) => tool.name).join(',');

      context.state.catalog = catalog;
      context.state.document = spec.document;
      context.state.origin = spec.origin;
      context.state.lastError = undefined;
      if (!wasReady) context.state.readyAt = new Date();

      if (!wasReady) {
        logger.info('ready', {
          origin: spec.origin,
          attempts: context.state.attempts,
          waitedSeconds: Math.round((Date.now() - context.state.startedAt.getTime()) / 1000),
          profile: catalog.profile.name,
          visible: catalog.visible.length,
          hidden: catalog.hidden.length,
          excluded: catalog.excluded.length,
          curated: catalog.visible.filter((tool) => tool.curated).length,
          readOnly: config.readOnly,
          authMode: config.authMode,
        });
        if (catalog.visible.length === 0) {
          // Never a working state, always a configuration mistake — most likely PROFILE and
          // READ_ONLY excluding each other, or a TOOLS_DENY that swallowed everything.
          logger.error('no tools are visible: check PROFILE, READ_ONLY, TOOLS_ALLOW, TOOLS_DENY', {
            profile: config.profile,
            readOnly: config.readOnly,
            toolsAllow: config.toolsAllow,
            toolsDeny: config.toolsDeny,
          });
        }
      } else if (before !== after) {
        logger.info('tool set changed after refreshing the OpenAPI document', {
          visible: catalog.visible.length,
        });
      }

      // A client that connected while the server was still loading saw an empty tool list.
      // On stdio the connection is long-lived, so it can be told; over HTTP each request is
      // its own session and the next `tools/list` is already correct.
      if (!wasReady || before !== after) {
        await stdioServer?.sendToolListChanged().catch(() => undefined);
      }

      if (config.specRefreshMinutes <= 0) return;
      await sleep(config.specRefreshMinutes * 60_000);
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      context.state.lastError = message;

      if (wasReady) {
        logger.warn('refreshing the OpenAPI document failed, keeping the previous catalogue', {
          error: message,
        });
        if (config.specRefreshMinutes <= 0) return;
        await sleep(config.specRefreshMinutes * 60_000);
      } else {
        const delay = backoffMs(context.state.attempts);
        logger.warn('the CMMS API document is not available yet, still trying', {
          attempt: context.state.attempts,
          retryInSeconds: Math.round(delay / 1000),
          error: message,
        });
        await sleep(delay);
      }
    }
  }
}

/** 5s, 10s, 20s, 40s, then a minute forever. */
function backoffMs(attempt: number): number {
  return Math.min(60_000, 5_000 * 2 ** Math.max(0, attempt - 1));
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms).unref?.();
  });
}

main().catch((error: unknown) => {
  // Only reachable for a genuinely unusable configuration — a missing CMMS_BASE_URL, an
  // unknown PROFILE, a port already taken. Anything about the CMMS being unreachable is
  // handled above and never lands here, because restarting cannot fix a slow neighbour.
  process.stderr.write(
    `${JSON.stringify({
      ts: new Date().toISOString(),
      level: 'error',
      message: 'cmms4fm-mcp cannot start with this configuration',
      error: error instanceof Error ? error.message : String(error),
    })}\n`,
  );
  process.exit(1);
});

export type { Config, Logger };
