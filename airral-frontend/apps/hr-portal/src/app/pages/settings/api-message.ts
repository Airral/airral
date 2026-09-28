/** The server's own explanation of a failed request when it gave one, otherwise the fallback. */
export function messageFrom(error: unknown, fallback: string): string {
  const failure = error as { status?: number; error?: { message?: string; validationErrors?: Record<string, string> } };
  const validation = failure?.error?.validationErrors;
  if (validation && Object.keys(validation).length) {
    return Object.values(validation)[0];
  }
  if (failure?.status && failure.status < 500 && failure.error?.message) {
    return failure.error.message;
  }
  return fallback;
}
