// Split one YAML line into coloured tokens (indent · list-dash · key · value/comment).
// Returns plain token objects the YamlView SFC renders as <span>s — the Vue analog of the
// React yamlTokens() that returned ReactNodes.

export interface YamlToken {
  text: string;
  cls?: string;
}

export function yamlTokens(line: string): YamlToken[] {
  // Runs on a single, bounded YAML line (non-global, anchored) — no ReDoS in practice.
  const m = /^(\s*)(-\s+)?(?:([\w.\-/]+)(:))?(\s*)(.*)$/.exec(line);
  if (!m) {
    return [{ text: line }];
  }
  const [, indent, dash, key, colon, gap, rest] = m;
  const tokens: YamlToken[] = [{ text: indent }];
  if (dash) {
    tokens.push({ text: dash, cls: 'yk-dash' });
  }
  if (key) {
    tokens.push({ text: key, cls: 'yk-key' }, { text: colon });
  }
  tokens.push({ text: gap });
  if (rest) {
    const t = rest.trim();
    let cls = 'yk-str';
    if (t.startsWith('#')) {
      cls = 'yk-comment';
    } else if (/^-?\d+(\.\d+)?$/.test(t)) {
      cls = 'yk-num';
    } else if (/^(true|false|null|~)$/i.test(t) || t === '|' || t === '>') {
      cls = 'yk-bool';
    }
    tokens.push({ text: rest, cls });
  }
  return tokens;
}
