// Copies text that is still being fetched when the user clicks. Follows
// Superset's own copyTextToClipboard (superset-ui-core/utils/copy), which
// extensions can't import.

const isSafari = () => /^((?!chrome|android).)*safari/i.test(navigator.userAgent);

/** Copies `text` once it resolves. Rejects with `text`'s own error if it
 * rejects, or with another error if the browser refuses the copy. */
export async function copyText(text: Promise<string>): Promise<void> {
  // Browsers only offer the Clipboard API on secure contexts (HTTPS, or
  // HTTP on localhost). Anything else is out of scope (004).
  if (!navigator.clipboard) {
    text.catch(() => {}); // not used, so don't leave a rejection unhandled
    throw new Error('No Clipboard API: not a secure context');
  }
  // Safari only allows a write during the click itself, so hand it the
  // pending text. Other browsers allow a write shortly after.
  if (isSafari() && typeof ClipboardItem !== 'undefined') {
    await navigator.clipboard.write([new ClipboardItem({ 'text/plain': text })]);
    return;
  }
  await navigator.clipboard.writeText(await text);
}
