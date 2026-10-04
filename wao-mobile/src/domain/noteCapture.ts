import type { Note } from './models';

export interface CaptureDraft {
  id: string;
  title: string;
  excerpt: string;
  thoughts: string;
  sourceUrl: string;
  sourceTitle: string;
  audioId?: string;
  audioIds?: string[];
  transcribedAudioIds?: string[];
  importedId?: string;
  rev?: number;
  imageUrl?: string;
}
export const escapeMarkdown = (text: string) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/([\\`*_{}\[\]()#+.!|~-])/g, '\\$1');
export function safeSourceUrl(value: string): string {
  try { const url = new URL(value); return ['https:', 'http:'].includes(url.protocol) ? url.href : ''; }
  catch { return ''; }
}
export function captureMarkdown(draft: CaptureDraft): string {
  if (!draft.excerpt && !draft.sourceUrl && !draft.sourceTitle && !draft.imageUrl) return draft.thoughts;
  const quote = draft.excerpt.replace(/\r\n?/g, '\n').split('\n').map(line => `> ${escapeMarkdown(line)}`).join('\n');
  const source = [draft.sourceTitle, safeSourceUrl(draft.sourceUrl)].filter(Boolean).join(' · ');
  const image = draft.imageUrl?.startsWith('/api/images/') ? `![书摘原图](${draft.imageUrl})\n\n` : '';
  return `${image}## 书摘\n\n${quote}\n\n${source ? `来源：${escapeMarkdown(source)}\n\n` : ''}## 我的感想\n\n${draft.thoughts}`;
}
export function capturePayload(draft: CaptureDraft) {
  return { title: draft.title.trim(), content: { version: 1, document: null, markdown: captureMarkdown(draft) },
    ...(draft.rev === undefined ? { createOnly: true } : { expectedRev: draft.rev }) };
}
export function quoteParts(note: Note): { prefix: string; thoughts: string } | null {
  const markdown = note.content?.markdown ?? '';
  const start = markdown.indexOf('## 书摘\n\n> ');
  const split = markdown.indexOf('\n\n## 我的感想\n\n', start);
  if (start < 0 || split < 0) return null;
  return { prefix: markdown.slice(0, split + '\n\n## 我的感想\n\n'.length), thoughts: markdown.slice(split + '\n\n## 我的感想\n\n'.length) };
}

export function newCapture(): CaptureDraft {
  return { id: `m-${crypto.randomUUID()}`, title: '', excerpt: '', thoughts: '', sourceUrl: '', sourceTitle: '' };
}
