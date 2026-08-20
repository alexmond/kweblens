import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

// ---- Every class `styles.css` selects on is a class something in the SPA writes (GH#473) ----
//
// Five rounds have now removed rules that reached no element: the 2026-08-06 audit (46), #447,
// #451 (`.grid`'s 17 rules and bare `.modal`), #456 and #467. Each was found by hand, each ticket
// says "filed so the next sweep does not have to rediscover it", and there was a next sweep every
// time. #473 asked for the sweep that ends the family, and a sweep that is not run again is a
// snapshot — so it is this test.
//
// WHAT IT ASSERTS: a relationship between two files. For every class named in a selector in
// `styles.css`, some `.vue` or `.ts` under `src/` puts that class on an element. It never reads a
// declaration, so rewording, retuning, reordering, adding or removing declarations cannot change
// its answer, and deleting a rule can never fail it. That is deliberate: #403, #447 and #471 each
// declined to assert a declaration present or absent, on the grounds that a stylesheet test which
// fails when a declaration is reworded is a test people delete. This one fails on exactly one
// event — a selector naming a class no template writes — which is the defect itself.
//
// HOW LIVENESS IS READ, and why not more simply. Only class POSITIONS count: `class` / `:class`
// (and `pane-class`, `row-class-name`, …) attribute values, plus single-token string literals in
// scripts, which is what a composed class name looks like (`'row-active'`). Asking instead
// whether the token appears anywhere in the sources is what let `.yaml-toggle .switch` survive
// four rounds of sweeping: `switch` is a JS keyword and a word in `'Add, edit or switch cluster'`,
// so a whole-word search over the sources reported a dead rule as live. A matcher that counts
// prose as an emission is a false-NEGATIVE machine — it makes the sweep find nothing.
//
// WHAT IT CANNOT SEE, stated so it is not mistaken for cover:
//  * a class assembled from something other than a leading literal (`kinds[k].cls`, a map lookup).
//    A leading literal IS handled — `'tone-' + s.tone` and `` `dx-sev-${sev}` `` claim the prefix —
//    but nothing else is, which is why exemptions exist rather than a stricter rule.
//  * a class a bundled library writes (`n-*` naive-ui, `cm-*`/`cm6-*` CodeMirror). Those are
//    exempted by prefix, so a rule naming a class naive has RENAMED still passes here.
//  * `<style>` blocks inside components. This reads `styles.css`, which is where the sheet lives.
//  * whether a live class ever appears UNDER the ancestor a descendant selector names. `.grid
//    tr.row-active td` was dead while `.row-active` in it was live (#451); only a browser can
//    answer that, and `contrast-check.mjs` / a runtime count is where it is answered.
const here = dirname(fileURLToPath(import.meta.url));
const css = readFileSync(resolve(here, 'styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/**
 * Classes a bundled library puts on the DOM, so no source of ours writes them.
 *
 * A prefix rather than a name each: the point is not to enumerate naive-ui, it is to say that
 * this half of the sheet is styling somebody else's markup and this check has nothing to say
 * about it. Each prefix must still cover something (below), so the list cannot outlive its libraries.
 */
const LIBRARY = ['n-', 'cm-', 'cm6-'];

/**
 * Classes of ours that nothing emits, each left in place on purpose for now.
 *
 * EMPTY, and that is the point (#505). The four it held — `.tabs`/`.tab` (the pre-Naive tab
 * strip), `.search` (the pre-Naive input) and `.act` (an action row) — were all judged dead and
 * their nine rules are gone, so the sheet is now a file this check has nothing to say "except"
 * about. An exemption list is the part of a sheet its own rule is not being applied to; leaving
 * one populated is how a check starts covering less than it claims.
 *
 * A new entry is a deliberate act with a ticket behind it, not a way to make a red run green:
 * the answer to "this rule selects on a class nothing writes" is almost always to delete the
 * rule. An entry that stops naming a dead class is itself a failure (below).
 */
/* eslint-disable sonarjs/no-empty-collection -- the list being empty is the ticket's outcome,
   not a leftover. Both assertions below must go on reading it: the first is what an entry would
   suppress, the second is what stops an entry outliving its rule, and deleting the mechanism
   because it currently exempts nothing is how the next exemption gets written with no guard on
   it. sonarjs is right that these reads cannot fail today; that is the state being defended. */
const KNOWN: string[] = [];

/** Every class token in a compound, e.g. `btn`,`danger` from `.btn.danger:hover`. */
const classesIn = (text: string): string[] => [...text.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)].map((m) => m[1]);

/** Every selector in the sheet, one per comma-separated part, whitespace normalised. */
const selectors = (): string[] => {
  const out: string[] = [];
  for (const chunk of css.split('}')) {
    const open = chunk.lastIndexOf('{');
    if (open === -1) continue;
    const prelude = chunk.slice(0, open);
    const list = prelude.slice(prelude.lastIndexOf('{') + 1);
    if (/^\s*@/.test(list)) continue; // an at-rule prelude is not a selector
    for (const one of list.split(',')) {
      const selector = one.trim().replace(/\s+/g, ' ');
      if (selector && !/^\d/.test(selector)) out.push(selector); // `50%` is a keyframe stop
    }
  }
  return out;
};

/** Every class any selector names, with the selectors it was named in. */
const selected = (): Map<string, Set<string>> => {
  const found = new Map<string, Set<string>>();
  for (const selector of selectors()) {
    for (const cls of classesIn(selector)) {
      const seen = found.get(cls) ?? new Set<string>();
      seen.add(selector);
      found.set(cls, seen);
    }
  }
  return found;
};

const sources = (): string[] => {
  const out: string[] = [];
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) walk(path);
      else if (/\.(vue|ts)$/.test(entry.name) && !/\.test\.ts$/.test(entry.name)) out.push(path);
    }
  };
  walk(here);
  return out;
};

/** What the sources claim to emit: exact class names, and prefixes from composed ones. */
interface Claims {
  exact: Set<string>;
  prefixes: Set<string>;
}

const TOKEN = /^-?[_a-zA-Z][\w-]*$/;

/**
 * The whitespace-separated class names in one literal.
 *
 * `*` marks where an interpolation was, so `tone-*` claims the PREFIX `tone-` and nothing exact;
 * `tailIsPrefix` says the same about a literal a `+` is about to extend (`'dx-item dx-' + sev`).
 * A tail is claimed both ways — `'leaf' + (on ? ' active' : '')` names a real class and opens one.
 */
const addParts = (literal: string, tailIsPrefix: boolean, claims: Claims): void => {
  const parts = literal.split(/\s+/).filter(Boolean);
  parts.forEach((part, i) => {
    const star = part.indexOf('*');
    const head = star === -1 ? part : part.slice(0, star);
    if (!head) return;
    if (star !== -1 || (tailIsPrefix && i === parts.length - 1)) claims.prefixes.add(head);
    if (TOKEN.test(head)) claims.exact.add(head);
  });
};

/** One class-position expression: template literals, quoted literals, a bare list, object keys. */
const harvest = (value: string, claims: Claims): void => {
  for (const m of value.matchAll(/`([^`]*)`/g)) addParts(m[1].replace(/\$\{[^}]*\}/g, '*'), false, claims);
  for (const re of [/'([^']*)'(\s*\+)?/g, /"([^"]*)"(\s*\+)?/g]) {
    for (const m of value.matchAll(re)) addParts(m[1], m[2] !== undefined && !/\s$/.test(m[1]), claims);
  }
  if (/['"`]/.test(value)) {
    // object syntax, `{ active: on, 'row-checked': checked }` — the keys are classes. Split rather
    // than match: an unanchored `[\w-]+` before a literal is quadratic on a long line, and eslint
    // (sonarjs/super-linear-regex) is right to refuse it here.
    for (const part of value.split(/[{},]/)) {
      const key = (part.split(':')[0] ?? '').trim().replace(/^['"`]|['"`]$/g, '');
      if (TOKEN.test(key)) claims.exact.add(key);
    }
  } else {
    addParts(value, false, claims); // a static class="a b"
  }
};

/** Values of every attribute whose name ends in `class`: `class`, `:class`, `pane-class`, … */
const classAttributes = (text: string): string[] => {
  const out: string[] = [];
  // Anchored on the word itself, so `:class`, `pane-class` and `row-class-name` all match and the
  // attribute-name prefix is never scanned. Matching the prefix instead (`[\w:.-]*class`) is the
  // quadratic shape eslint refuses, and it would have to be written twice for the two quote styles.
  for (const m of text.matchAll(/class(?:-?name)?\s*=\s*(["'])/gi)) {
    const from = (m.index ?? 0) + m[0].length;
    const end = text.indexOf(m[1], from);
    if (end !== -1) out.push(text.slice(from, end));
  }
  return out;
};

const scriptLiterals = (script: string, claims: Claims): void => {
  for (const re of [/'([^'\\\n]*)'/g, /"([^"\\\n]*)"/g, /`([^`\\$]*)`/g]) {
    // a single-token literal is what a composed class name looks like; one with a space is prose
    for (const m of script.matchAll(re)) if (!/\s/.test(m[1]) && TOKEN.test(m[1])) claims.exact.add(m[1]);
  }
};

const emissions = (files: { name: string; text: string }[]): ((cls: string) => boolean) => {
  const claims: Claims = { exact: new Set(), prefixes: new Set() };
  for (const file of files) {
    const isTemplate = /\.vue$/.test(file.name);
    if (isTemplate) for (const value of classAttributes(file.text)) harvest(value, claims);
    scriptLiterals(isTemplate ? (/<script[^>]*>([\s\S]*?)<\/script>/.exec(file.text)?.[1] ?? '') : file.text, claims);
  }
  return (cls) => claims.exact.has(cls) || [...claims.prefixes].some((p) => cls.startsWith(p) && cls !== p);
};

const emitted = emissions(sources().map((name) => ({ name, text: readFileSync(name, 'utf8') })));
const named = selected();
const unwritten = [...named.keys()].filter((c) => !emitted(c) && !LIBRARY.some((p) => c.startsWith(p))).sort();

describe('every class styles.css selects on', () => {
  it('is written by something in the SPA, beyond the ones written down', () => {
    // Named, not counted: the value of this check is that the answer is one selector away.
    expect(
      unwritten
        .filter((c) => !KNOWN.includes(c))
        .map((c) => '.' + c + ' -> ' + [...(named.get(c) as Set<string>)].join(' | ')),
      'these rules select on a class no template writes — remove the rule, or fix the name',
    ).toEqual([]);
  });

  it('exempts only classes that are still dead', () => {
    expect(
      KNOWN.filter((c) => !unwritten.includes(c)),
      'exempted but now emitted (or gone) — delete the entry',
    ).toEqual([]);
  });

  it('exempts only libraries the sheet still styles', () => {
    const covers = LIBRARY.filter((p) => [...named.keys()].some((c) => c.startsWith(p)));
    expect(covers, 'a library prefix that covers nothing is a blanket nobody needs').toEqual(LIBRARY);
  });

  it('is a check with teeth — it calls the #473 rule dead and the composed names live', () => {
    // A check written after the fix proves only that it is quiet. This is the matcher run against
    // the emission shapes this repo actually uses, including the one that hid #473 for four rounds.
    const live = emissions([
      { name: 'a.vue', text: '<template><label class="yaml-toggle"><NSwitch /></label></template>' },
      { name: 'b.vue', text: `<template><li :class="'ov-state tone-' + s.tone" /></template>` },
      { name: 'c.vue', text: '<template><span :class="`drawer-badge tone-${b.tone}`" /></template>' },
      { name: 'd.vue', text: '<template><NTabs pane-class="drawer-body" /></template>' },
      { name: 'e.ts', text: `const cls = 'row-active';\nswitch (state) { default: }` },
      { name: 'f.vue', text: '<template><input placeholder="Search or switch cluster" /></template>' },
    ]);
    expect(live('yaml-toggle')).toBe(true); // the live ancestor
    expect(live('switch')).toBe(false); // the dead descendant — a JS keyword and prose, not a class
    expect(live('tone-ok')).toBe(true); // composed with `+`
    expect(live('tone-warn')).toBe(true); // composed by interpolation
    expect(live('drawer-badge')).toBe(true); // a literal beside a composed one
    expect(live('drawer-body')).toBe(true); // handed to a component as `pane-class`
    expect(live('row-active')).toBe(true); // a single-token literal in a script
    expect(live('ov-state')).toBe(true);
    expect(live('menu-danger')).toBe(false); // a class none of these files writes
  });
});
