// Markdown from the repository (READMEs), rendered to HTML that's safe to
// put in the page (008): HTML written in it is shown as text, only absolute
// http(s) links are links (opening in a new tab), and images show their alt
// text instead of loading.
import MarkdownIt from 'markdown-it';

const md = new MarkdownIt({ html: false, linkify: false });

const isSafeLink = (href: string) => /^https?:\/\//i.test(href);

// Every link is let through parsing, so an unsafe one still shows its text
// (markdown-it would otherwise leave its source, brackets and all), and the
// renderer below decides whether it becomes a link.
md.validateLink = () => true;

md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx];
  const safe = isSafeLink(token.attrGet('href') ?? '');
  // So the matching link_close knows whether to close anything.
  (env.links ??= []).push(safe);
  if (!safe) return '';
  token.attrSet('target', '_blank');
  token.attrSet('rel', 'noopener noreferrer');
  return self.renderToken(tokens, idx, options);
};
md.renderer.rules.link_close = (tokens, idx, options, env, self) =>
  env.links.pop() ? self.renderToken(tokens, idx, options) : '';

md.renderer.rules.image = (tokens, idx, options, env, self) =>
  md.utils.escapeHtml(
    self.renderInlineAsText(tokens[idx].children ?? [], options, env),
  );

/** A paragraph's worth of Markdown (no blocks), as HTML. */
export const renderInline = (text: string): string =>
  md.renderInline(text, {});

/** A Markdown document, as HTML. */
export const render = (text: string): string => md.render(text, {});
