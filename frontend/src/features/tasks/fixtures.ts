import { examples } from '../../mocks/examples.generated';
import type { components } from '../../api/schema';

export function example<T>(operationId: string): T {
  return structuredClone(
    examples.find((entry) => entry.operationId === operationId)!.responses.find((response) => response.status < 300)!
      .body,
  ) as T;
}
export type Schemas = components['schemas'];
