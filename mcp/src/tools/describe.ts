import type { Operation } from '../openapi/operations.js';

/**
 * Read/write classification and descriptions for operations the curated layer does not
 * cover.
 *
 * **Why classification cannot come from the HTTP method alone.** The concept derives
 * `readOnlyHint` from the method (GET reads, DELETE/PUT write), which is the right default
 * and wrong for this API in one decisive place: every list endpoint is a **POST** carrying
 * a `SearchCriteria` body — `POST /assets/search`, `POST /work-orders/search` — and so is
 * every analytics query. Classify those as writing and `PROFILE=readonly` loses the ability
 * to find anything, which makes the read-only deployment useless rather than safe.
 *
 * So the rule is: GET reads; POST reads when it is one of the query shapes below; anything
 * else writes. The list is explicit rather than heuristic, so an upstream endpoint that
 * introduces a new query shape is classified as writing until someone looks at it — the
 * error that costs a hidden tool, not the one that exposes a write.
 */

/**
 * Appended to every tool whose PATCH replaces the record. Worded as an instruction rather than
 * a caveat, because the correct sequence is not obvious from the verb.
 */
export const PATCH_REPLACES_WARNING =
  'Despite being a PATCH, this replaces the whole record: every field you leave out is cleared (text becomes empty, numbers become 0). Read the record first, change what you mean to change, and send all of it back.';

/** POST paths that only query. Matched against the template path from the document. */
const READ_ONLY_POST_PATTERNS: RegExp[] = [
  /^\/analytics\//,
  /\/search$/,
  /\/search\//,
  /\/histogram$/,
  /^\/work-orders\/events$/,
];

/**
 * Endpoints that are never offered as a tool, in any profile.
 *
 * This is not a second permission system (konzept non-goal): every one of these stays
 * reachable over REST for whoever holds the key, and the CMMS decides who may call it. It
 * is tool-surface hygiene — an agent has no business issuing credentials, ending its own
 * session, changing what the organisation pays, or registering a push device, and offering
 * those as tools only adds a way for an injected instruction to reach them.
 */
const BLOCKED_PATH_PATTERNS: RegExp[] = [
  /^\/auth\//,
  /^\/subscriptions\//,
  /^\/subscription-plans/,
  /^\/notifications\/push-token$/,
];

export interface Classification {
  readOnly: boolean;
  destructive: boolean;
  idempotent: boolean;
}

export function classify(operation: Operation): Classification {
  const readOnly =
    operation.method === 'get' ||
    (operation.method === 'post' &&
      READ_ONLY_POST_PATTERNS.some((pattern) => pattern.test(operation.path)));

  if (readOnly) {
    return { readOnly: true, destructive: false, idempotent: true };
  }

  switch (operation.method) {
    case 'delete':
      return { readOnly: false, destructive: true, idempotent: true };
    case 'put':
    case 'patch':
      // Changes an existing record in place: destructive in the MCP sense (the previous
      // value is gone), but repeating it lands on the same result.
      return { readOnly: false, destructive: true, idempotent: true };
    default:
      // POST that is not a query: creates something new, so repeating it creates again.
      return { readOnly: false, destructive: false, idempotent: false };
  }
}

export function isBlocked(operation: Operation): boolean {
  return BLOCKED_PATH_PATTERNS.some((pattern) => pattern.test(operation.path));
}

/**
 * Builds the description for an operation the curated layer does not name. It is assembled
 * from the only facts the document reliably carries — tag, method, path, parameter names
 * and the response DTO — and says so, so a model can tell a described tool from a guessed
 * one and prefer the curated ones.
 */
/**
 * `PATCH` in this API replaces the record rather than merging into it.
 *
 * The mappers are MapStruct update methods (`X updateX(@MappingTarget X entity, XPatchDTO
 * dto)`) and none of them sets `nullValuePropertyMappingStrategy = IGNORE` — 1 of 58 does, and
 * that one is a file this fork added. MapStruct's default is `SET_TO_NULL`, so a field absent
 * from the body is written as null, and an absent primitive as 0 or false.
 *
 * The web frontend never notices, because its edit forms always post the complete object. An
 * agent doing what the word "patch" means sends only the field it wants to change — and either
 * destroys the rest of the record or, where a column is `@NotNull` (`Part.name` is), fails with
 * an error that says nothing about the real cause. That is what happened when an attempt to set
 * a part's quantity kept failing.
 *
 * Detected by the body schema ending in `PatchDTO`, which is exactly the set mapped onto a whole
 * entity — 46 of the 69 PATCH operations. The other 23 take purpose-built bodies
 * (`WorkOrderChangeStatusDTO`) or none at all and behave as their name suggests.
 */
export function replacesWholeRecord(operation: Operation): boolean {
  if (operation.method !== 'patch') return false;
  const ref = operation.body?.schema.$ref;
  return typeof ref === 'string' && ref.endsWith('PatchDTO');
}

export function synthesiseDescription(operation: Operation): string {
  const parts: string[] = [];

  const documented = operation.summary ?? operation.description;
  if (documented) parts.push(documented.trim());

  parts.push(`${operation.tag}: ${operation.method.toUpperCase()} ${operation.path}.`);

  if (operation.responseSchema) {
    parts.push(`Answers with ${operation.responseSchema}.`);
  }

  if (operation.body) {
    const title = operation.body.schema.$ref;
    const name =
      typeof title === 'string' ? title.split('/').pop() : (operation.body.schema.title as string);
    parts.push(
      name
        ? `Send the payload in "body" (${name}).`
        : 'Send the payload in "body".',
    );
  }

  if (operation.queryParams.length > 0) {
    parts.push(`Query parameters: ${operation.queryParams.map((p) => p.name).join(', ')}.`);
  }

  if (replacesWholeRecord(operation)) parts.push(PATCH_REPLACES_WARNING);

  if (operation.deprecated) parts.push('Deprecated in the API.');

  if (!documented) {
    parts.push(
      'Description generated from the OpenAPI document, which carries no text for this endpoint.',
    );
    if (!classify(operation).readOnly) {
      // A wrong read returns visibly wrong data. A wrong write changes a different record and
      // reports success, which is what happened when a request to book stock onto a part
      // landed on PATCH /part-quantities/{id} — a work order line, not inventory, and named
      // closely enough to be the best match. The generated layer cannot know the difference,
      // so it should say that rather than sound as confident as a curated tool.
      parts.push(
        'This is a writing operation and nothing describes what it changes, so the entity behind the path may not be what its name suggests. Prefer a tool with a written description, or read the record back afterwards to confirm.',
      );
    }
  }

  return parts.join(' ');
}
