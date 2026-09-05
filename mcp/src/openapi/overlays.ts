import type { JsonSchema } from './types.js';

/**
 * Facts about a schema that the OpenAPI document leaves out, added back so a caller can see
 * them.
 *
 * This is transcription, not invention: every value below is read off the Java class that
 * springdoc generated the schema from, and the CMMS behaves this way whether or not the
 * schema says so. Nothing here changes a request — it only changes what the tool advertises.
 *
 * The one that matters is `SearchCriteria`. Its fields are all optional with sensible
 * server-side defaults (`api/.../advancedsearch/SearchCriteria.java`), but springdoc emits
 * neither the defaults nor the fact that they are optional. A client that dutifully fills
 * every field it sees therefore invents values — n8n's manual input mode produces
 * `"field": "string"` and `pageSize: 0` — and the call fails with
 * `500 "Page size must not be less than one"`, while *omitting* the same fields would have
 * worked. An LLM composing the call has exactly the same problem.
 *
 * Keep this table small and keep every entry traceable to a line of Java. It is not a place
 * for opinions about how the API should be used.
 */
export const SCHEMA_OVERLAYS: Record<string, JsonSchema> = {
  SearchCriteria: {
    description:
      'Every field is optional and the server fills in the defaults shown here — send only what you want to change. `filterFields` entries are combined with AND.',
    properties: {
      filterFields: { default: [] },
      direction: { default: 'ASC' },
      pageNum: { default: 0, minimum: 0 },
      // `PageRequest.of` rejects 0, and the CMMS surfaces that as a 500 rather than a 400.
      pageSize: { default: 10, minimum: 1 },
      sortField: { default: 'id' },
    },
  },
};

/**
 * Merges an overlay into a resolved schema. Property entries are merged field by field, so
 * an overlay adds `default` and `minimum` without discarding the type and description the
 * document already carries.
 */
export function applyOverlay(name: string, schema: JsonSchema): JsonSchema {
  const overlay = SCHEMA_OVERLAYS[name];
  if (!overlay) return schema;

  const result: JsonSchema = { ...schema };

  if (typeof overlay.description === 'string') {
    result.description = [schema.description, overlay.description].filter(Boolean).join(' ');
  }

  const overlayProperties = overlay.properties as Record<string, JsonSchema> | undefined;
  const schemaProperties = schema.properties as Record<string, JsonSchema> | undefined;
  if (overlayProperties && schemaProperties) {
    const merged: Record<string, JsonSchema> = { ...schemaProperties };
    for (const [property, addition] of Object.entries(overlayProperties)) {
      // Only annotate properties the document actually has: a property that disappeared
      // upstream should vanish from the tool too, not be resurrected by this table.
      if (!merged[property]) continue;
      merged[property] = { ...merged[property], ...addition };
    }
    result.properties = merged;
  }

  return result;
}
