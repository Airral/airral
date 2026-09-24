/**
 * Take the one-time link off the page before anything else can see it.
 *
 * <p>Firebase lands the person here with the link's secret (oobCode) in the query
 * string. Held in memory, then removed from the address bar, so it is not left in
 * history, bookmarks, a screenshot, or a Referer header sent by the next click.
 * The page also declares no-referrer while it is open, per OWASP's guidance for
 * pages that receive reset tokens.
 */
export function captureEmailLink(doc: Document): string {
  const view = doc.defaultView;
  if (!view) return '';
  const href = view.location.href;
  const url = new URL(href);
  if (url.searchParams.has('oobCode') || url.searchParams.has('mode')) {
    view.history.replaceState(null, '', url.pathname);
  }
  if (!doc.querySelector('meta[name="referrer"][content="no-referrer"]')) {
    const meta = doc.createElement('meta');
    meta.name = 'referrer';
    meta.content = 'no-referrer';
    doc.head.appendChild(meta);
  }
  return href;
}

