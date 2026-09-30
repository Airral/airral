/**
 * The server's own explanation of a failed request when it gave one, otherwise the fallback.
 *
 * Requests through ApiClientService fail with an Error whose message is the
 * server's message and whose status is the HTTP status. A raw
 * HttpErrorResponse carries them under error instead. Both are read here.
 * Angular's own "Http failure response for ..." text is never shown.
 */
export function messageFrom(error: unknown, fallback: string): string {
  const failure = error as {
    status?: number;
    message?: string;
    error?: { message?: string; validationErrors?: Record<string, string> };
  };
  const validation = failure?.error?.validationErrors;
  if (validation && Object.keys(validation).length) {
    return Object.values(validation)[0];
  }
  if (failure?.status && failure.status < 500) {
    const message = failure.error?.message ?? failure.message;
    if (message && !message.startsWith('Http failure')) {
      return message;
    }
  }
  return fallback;
}
