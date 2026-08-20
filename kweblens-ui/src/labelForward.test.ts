import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { forwardsLabelClick, SELF_ACTIVATING } from './labelForward';

// ---- An affordance either operates something, or it is not painted (GH#506) ----
//
// Two halves of one rule, and they need two different kinds of check.
//
// THE DECISION (`forwardsLabelClick`, below) is the half that ships as code, and it exists
// because "make the label work" invites exactly one bug: a click on the CONTROL bubbles up to
// the wrapper's handler as well, so the control toggles itself once and the wrapper toggles it
// again. On a two-state control a double toggle is invisible AS a double — it reads as a switch
// that has stopped working. Measured on a running instance with the guard removed: clicking the
// switch produced **2** `aria-checked` transitions and left the state where it started. So the
// guard is a named predicate rather than three lines inline, and the case it exists for is the
// case asserted first.
//
// THE SWEEP (further down) is the half that cannot be a runtime probe. `cursor: pointer` says
// "this is a click target"; measured on a running instance, `.yaml-toggle` painted it over words
// that produced **zero** state changes, because the `<label>` wrapped an `NSwitch` — a
// `<div role="switch">`, not a labelable element — and `label.control` was `null`. A browser
// sweep did find that (1 of 294 pointer-cursor elements on the YAML tab forwarded nothing, read
// through CDP's `DOMDebugger.getEventListeners`), but a browser only sees what a scene can bring
// on screen, and four of this SPA's eight `<label>`s live behind a Helm form and a pod file
// browser that is off by default. This check reads the declaration instead: `styles.css` says
// which classes paint the affordance, the templates say which element carries each class, and an
// element that carries one must activate something.
//
// WHAT IT CANNOT SEE, stated so it is not mistaken for cover:
//  * a pointer cursor INHERITED from an ancestor rule. Only the element carrying the class that
//    declares it is checked, which is the right subject — the ancestor is the click target and
//    the descendants are its text. Measured: of 286 pointer-cursor elements on the Pods list,
//    every one was either native or inside something with a listener.
//  * whether the handler it finds does anything. `@click` present is the test; a handler with an
//    empty body passes here and is a different defect.
//  * a control that has no accessible NAME. Measured: naive-ui's `NSelect` renders no
//    `role="combobox"` anywhere in its markup — the only role under `.bar-filter` is
//    `role="img"` on the loading spinner — so no `aria-label` on it would reach assistive tech
//    and there is nothing here to assert. `NSwitch` does carry `role="switch"`, which is why the
//    YAML toggle can name itself and the two brand-bar filters cannot.
const here = dirname(fileURLToPath(import.meta.url));

describe('forwardsLabelClick — the double toggle this fix invites', () => {
  /** The `<div role="switch">` NSwitch renders — what a click can land on inside the label. */
  const theSwitch = { what: 'role=switch' };
  /** A stand-in for a click target: `closest` answers with `found`, or `null` for the words. */
  const target = (found: unknown) => ({ closest: () => found });
  /** The label. `contains` says whether a hit is inside it. */
  const label = (inside: unknown[]) => ({ contains: (n: unknown) => inside.includes(n) });

  it('forwards a click that landed on the WORDS', () => {
    // Nothing self-activating between the text node and the document root.
    expect(forwardsLabelClick(target(null), label([theSwitch]))).toBe(true);
  });

  it('does NOT forward a click that landed on the switch — this is the double toggle', () => {
    // `closest` finds the `role="switch"` div and it is inside the label: the control has
    // already toggled itself, so forwarding would put the state straight back.
    expect(forwardsLabelClick(target(theSwitch), label([theSwitch]))).toBe(false);
  });

  it('still forwards when the self-activating ancestor is OUTSIDE the label', () => {
    // `closest` walks to the document root, so a toolbar that is itself a button would
    // otherwise suppress every forward from every label inside it.
    expect(forwardsLabelClick(target(theSwitch), label([]))).toBe(true);
  });

  it('does not forward a target that is not an element', () => {
    expect(forwardsLabelClick(null, label([]))).toBe(false);
    expect(forwardsLabelClick({}, label([]))).toBe(false);
  });

  it('names the roles a naive control declares, not a naive class name', () => {
    // The guard has to recognise `NSwitch`'s root, and `.n-switch` is a fact about a release
    // while `role="switch"` is a fact about the control (measured on the running app:
    // `role=switch tabindex=0`).
    expect(SELF_ACTIVATING).toContain('[role=switch]');
    expect(SELF_ACTIVATING).not.toContain('n-');
  });
});

// ---------------------------------------------------------------------------------------
// The sweep: every element painting `cursor: pointer` activates something.
// ---------------------------------------------------------------------------------------

/** Native tags that activate on a click without anyone writing a handler. */
const NATIVE = /^(button|a|summary|input|select|textarea|option)$/;
/** Elements a `<label>` genuinely forwards a click to — HTML's "labelable". */
const LABELABLE = /<(input|select|textarea|button|meter|output|progress)\b/;

/** Every class whose own rule declares `cursor: pointer`, taken from the selector's SUBJECT. */
const pointerClasses = (): Set<string> => {
  const css = readFileSync(join(here, 'styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');
  const out = new Set<string>();
  for (const chunk of css.split('}')) {
    const open = chunk.lastIndexOf('{');
    if (open === -1 || !/cursor:\s*pointer/.test(chunk.slice(open + 1))) continue;
    const prelude = chunk.slice(0, open);
    for (const one of prelude.slice(prelude.lastIndexOf('{') + 1).split(',')) {
      // `.a .b`, `.a > .b` — the subject is the last compound, which is the element painted.
      const last = one
        .trim()
        .split(/[\s>]+/)
        .filter(Boolean)
        .pop();
      const cls = last ? [...last.matchAll(/\.(-?[_a-zA-Z][\w-]*)/g)].map((m) => m[1]).pop() : undefined;
      if (cls) out.add(cls);
    }
  }
  return out;
};

const vueFiles = (dir: string): string[] =>
  readdirSync(dir, { withFileTypes: true }).flatMap((e) =>
    e.isDirectory() ? vueFiles(join(dir, e.name)) : e.name.endsWith('.vue') ? [join(dir, e.name)] : [],
  );

interface OpenTag {
  tag: string;
  attrs: string;
  /** Index just past the `>`, so the caller can read the element's content. */
  end: number;
}

/**
 * Every opening tag in a template, SCANNED rather than pattern-matched.
 *
 * A regex that lets a quoted attribute value contain `>` needs alternation inside a repetition,
 * which backtracks (eslint's `sonarjs/super-linear-regex` refuses it, rightly). And a regex that
 * stops at the first `>` is wrong here for a reason this project's templates hit on nearly every
 * component: `@update:value="(v) => setField(f, v)"` puts a `>` inside the attribute list, so the
 * naive pattern truncates the attributes and reports a handled element as unhandled. A one-pass
 * scan that tracks the quote state is linear and gets both right.
 */
const openingTags = (text: string): OpenTag[] => {
  const out: OpenTag[] = [];
  for (let i = text.indexOf('<'); i !== -1; i = text.indexOf('<', i + 1)) {
    const name = /^<([A-Za-z][\w.-]*)/.exec(text.slice(i, i + 64));
    if (!name) continue;
    let j = i + name[0].length;
    let quote = '';
    for (; j < text.length; j++) {
      const c = text[j];
      if (quote) {
        if (c === quote) quote = '';
      } else if (c === '"' || c === "'") quote = c;
      else if (c === '>') break;
    }
    out.push({ tag: name[1], attrs: text.slice(i + name[0].length, j), end: j + 1 });
  }
  return out;
};

/** The classes this tag writes: a `class="…"` list, plus single-token literals inside `:class`. */
const classesOn = (attrs: string): string[] => [
  ...[...attrs.matchAll(/\bclass="([^"]*)"/g)].flatMap((c) => c[1].split(/\s+/)),
  ...[...attrs.matchAll(/'([\w-]+)'/g)].map((c) => c[1]),
];

/**
 * Does a click on this element operate anything?
 *
 * @param body the element's content, needed only for the `<label>` arm
 */
const activates = ({ tag, attrs }: Pick<OpenTag, 'tag' | 'attrs'>, body: string): boolean => {
  if (NATIVE.test(tag)) return true; // activates itself
  if (/^[A-Z]/.test(tag)) return true; // a component; its own root decides
  if (/@click|@mousedown|v-on:click/.test(attrs)) return true; // a handler was written
  // A `<label>` activates whatever LABELABLE element it contains — and ONLY then. This is the
  // arm GH#506 fell through: `<label class="yaml-toggle">` held an `NSwitch`, which renders a
  // `<div role="switch">`, so there was no control and the pointer cursor lied.
  return tag === 'label' && LABELABLE.test(body);
};

/**
 * Every element in the SPA's templates that carries a pointer-cursor class and activates nothing.
 *
 * A list of sentences rather than a count: a count tells you the gate went red and nothing else,
 * and the point of this family of checks is that the failure names the file and the element.
 */
const claimsWithoutActing = (classes: Set<string>): string[] => {
  const bad: string[] = [];
  for (const file of vueFiles(here)) {
    const text = readFileSync(file, 'utf8');
    for (const open of openingTags(text)) {
      const hit = classesOn(open.attrs).filter((c) => classes.has(c));
      if (!hit.length) continue;
      const close = text.indexOf(`</${open.tag}>`, open.end);
      if (activates(open, close === -1 ? '' : text.slice(open.end, close))) continue;
      bad.push(`${relative(here, file)}  <${open.tag} class="… ${hit.join(' ')} …"> activates nothing`);
    }
  }
  return bad;
};

describe('a pointer cursor is painted only over something that activates', () => {
  it('finds the classes that declare it', () => {
    const classes = pointerClasses();
    // A sweep that matched nothing would pass silently, which is the failure mode this whole
    // family of checks exists to avoid. 31 selectors carry the declaration today.
    expect(classes.size).toBeGreaterThan(20);
    expect(classes.has('yaml-toggle')).toBe(true);
  });

  it('every element carrying one of them activates something', () => {
    expect(claimsWithoutActing(pointerClasses())).toEqual([]);
  });

  it('fires on GH#506 — a pointer-cursor label wrapping a control that is not labelable', () => {
    // The positive control, and it is the point of the check: a gate whose verdict does not
    // change between the defect and the fix is worse than no gate. Scanned through the same
    // functions the sweep uses, because a control that reimplements the check tests the copy.
    const scan = (markup: string): boolean => {
      const [open] = openingTags(markup);
      const close = markup.indexOf(`</${open.tag}>`, open.end);
      return !activates(open, close === -1 ? '' : markup.slice(open.end, close));
    };
    // The markup that shipped.
    expect(scan(`<label class="yaml-toggle" title="x"><NSwitch v-model:value="h" /> Hide Managed Fields</label>`)).toBe(
      true,
    );
    // The markup that replaced it — not a false positive.
    expect(
      scan(
        `<span class="yaml-toggle" title="x" @click="onToggleClick"><NSwitch v-model:value="h" /> Hide Managed Fields</span>`,
      ),
    ).toBe(false);
    // And the shape that is fine and must stay fine: a real label round a real checkbox
    // (`.dock-toggle`, which paints the same cursor and has always worked).
    expect(scan(`<label class="dock-toggle"> <input v-model="wrap" type="checkbox" /> wrap </label>`)).toBe(false);
  });

  it('reads an attribute list containing a fat arrow', () => {
    // `@update:value="(v) => setField(f, v)"` puts a `>` inside the attributes. A scanner that
    // stopped at the first `>` would report this element as having no handler at all.
    const [open] = openingTags(`<div class="cp-link" @click="() => go(1)" @update:value="(v) => set(v)">x</div>`);
    expect(open.attrs).toContain('@update:value');
    expect(activates(open, 'x')).toBe(true);
  });
});
