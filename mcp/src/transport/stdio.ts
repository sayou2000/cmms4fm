import type { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';

import { createMcpServer, type ServerContext } from '../server.js';

/**
 * stdio, for a desktop client that launches the server itself (konzept E7). One process,
 * one client, one key from the environment (`CMMS_API_KEY`), because there is no HTTP
 * request to carry a header on.
 *
 * Everything this process writes to stdout is protocol; logs go to stderr (see logger.ts).
 */
export async function startStdioTransport(context: ServerContext): Promise<Server> {
  const server = createMcpServer(context);
  const transport = new StdioServerTransport();

  context.logger.info('MCP server on stdio', {
    authMode: context.config.authMode,
  });

  await server.connect(transport);
  // Returned so the caller can announce the tool list once the document has loaded: on stdio
  // the connection is long-lived, so a client that connected during startup can be told
  // rather than left with the empty list it first saw.
  return server;
}
