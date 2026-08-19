import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { TONE_VARS } from './statusTones';

// The semantic TINTS, checked as the colours they become — the same trick `barTones.test.ts`
// and `severityTones.test.ts` use, and for the same reason: the tokens live in `styles.css`,
// Vitest does not process CSS, so the stylesheet is read off disk and parsed rather than
// rendered. Comments are stripped first; a token that only exists in prose is not a token.
//
// What this pins is #482. A tint is `rgba(…)`, so what a reader sees is never the declared
// value — it is that value composited over the panel underneath, and the two are far enough
// apart that reasoning about the declaration is how the defect shipped. The dark warn tint was
// `--warn-fg`'s own amber at 16%, which over `rgb(31, 36, 42)` lands on rgb(67, 61, 49): hue
// 41°, saturation 15%, lightness 23% — a brown, next to the danger tint's rgb(67, 52, 56) at
// the SAME lightness and the same near-zero chroma. Measured ΔE 10.9, i.e. two grades of
// severity that a reader has to take on trust rather than see.
//
// So the assertions are about relationships between composited colours, never about a literal:
// a hex here would fail on any deliberate retune and prove nothing about the thing that broke.
// Three claims, each of which was false at some point in this file's history:
//
//   * a tint must be distinguishable from the PANEL, or a tinted row is an untinted row;
//   * two states' tints must be distinguishable from EACH OTHER, or the vocabulary collapses;
//   * a warm state's WASH must not composite cooler than the panel it lies on — the trap #482
//     met when it moved the warn base and carried its old alpha over, because this app's greys
//     are deliberately blue-shifted (#478: B−R ≈ +22) and a low enough alpha lets the ground
//     win the red-over-green comparison outright.
const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, '');

/**
 * Every rule in the sheet as `{ selectors, body }`.
 *
 * A third copy of the parser `barTones.test.ts` explains at length, for the reason it gives:
 * four lines of text handling belong to the check that reads them, and a shared module between
 * three test files would be a dependency in the direction that makes each one harder to read.
 */
const rules = css.split('}').flatMap((chunk) => {
  const open = chunk.lastIndexOf('{');
  if (open === -1) return [];
  const prelude = chunk.slice(0, open);
  return [
    {
      selectors: prelude
        .slice(prelude.lastIndexOf('{') + 1)
        .split(',')
        .map((s) => s.trim()),
      body: chunk.slice(open + 1),
    },
  ];
});

/**
 * The value of a custom property inside a block, e.g. `--warn-tint` in `html.kw-dark`.
 *
 * Every rule carrying the selector is searched rather than the first one: `:root` opens four
 * separate blocks in this sheet (the palette, the `--*-on-tint` constructions, the table tokens
 * and the drawer's), so "the first `:root`" is a coincidence of ordering, and a check that
 * depends on it reports "no such token" the day someone moves a block.
 */
function token(block: string, name: string): string {
  for (const rule of rules.filter((r) => r.selectors.includes(block))) {
    const decl = new RegExp(`(^|[;{\\s])${name}:\\s*([^;]+);`).exec(rule.body);
    if (decl) return decl[2].trim();
  }
  expect.fail(`${block} declares no ${name}`);
}

type Rgb = [number, number, number];

/** `#rrggbb` or `rgba(r, g, b, a)` as channels plus alpha. A token in any other form fails. */
function colour(value: string): { rgb: Rgb; a: number } {
  const hex = /^#([0-9a-f]{6})$/i.exec(value);
  if (hex) {
    const n = parseInt(hex[1], 16);
    return { rgb: [(n >> 16) & 255, (n >> 8) & 255, n & 255], a: 1 };
  }
  const fn = /^rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)(?:[\s,/]+([\d.]+))?\s*\)$/.exec(value);
  expect(fn, `"${value}" is neither #rrggbb nor rgb(a)(), so this check cannot composite it`).not.toBeNull();
  const m = fn as RegExpExecArray;
  return { rgb: [Number(m[1]), Number(m[2]), Number(m[3])], a: m[4] === undefined ? 1 : Number(m[4]) };
}

/** `src` at its own alpha over `dst` — what the reader actually sees. */
const over = (src: { rgb: Rgb; a: number }, dst: Rgb): Rgb =>
  dst.map((c, i) => src.a * src.rgb[i] + (1 - src.a) * c) as Rgb;

const linear = (c: number) => (c / 255 <= 0.04045 ? c / 255 / 12.92 : ((c / 255 + 0.055) / 1.055) ** 2.4);

/** CIE L*a*b* (D65), so a separation is a perceptual distance and not an sRGB one. */
function lab(c: Rgb): [number, number, number] {
  const [R, G, B] = c.map(linear);
  const xyz = [
    (R * 0.4124 + G * 0.3576 + B * 0.1805) / 0.95047,
    R * 0.2126 + G * 0.7152 + B * 0.0722,
    (R * 0.0193 + G * 0.1192 + B * 0.9505) / 1.08883,
  ].map((t) => (t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16 / 116));
  return [116 * xyz[1] - 16, 500 * (xyz[0] - xyz[1]), 200 * (xyz[1] - xyz[2])];
}

/** CIE76 ΔE. Crude next to ΔE2000 and deliberately so: it is arithmetic anyone can check. */
function deltaE(a: Rgb, b: Rgb): number {
  const [p, q] = [lab(a), lab(b)];
  return Math.hypot(p[0] - q[0], p[1] - q[1], p[2] - q[2]);
}

/** Both palettes. A tone tuned in one of them only is the defect this whole family collects. */
const THEMES = [':root', 'html.kw-dark'];
const STATES = ['ok', 'warn', 'danger'];

/**
 * The composited tint of every state, per theme.
 *
 * `--panel` is the ground: it is what the drawer, the cards and — since #478 pointed naive's
 * `DataTable` at these same tokens — the list rows all paint, so it is the ground for nearly
 * every tinted surface in the app. A pill inside a naive modal sits on a slightly lighter card
 * and is not covered here; that one is measured on the running app by `contrast-check.mjs`.
 */
const composited = (theme: string, role: string) =>
  Object.fromEntries(
    STATES.map((s) => [s, over(colour(token(theme, `--${s}-${role}`)), colour(token(theme, '--panel')).rgb)]),
  ) as Record<string, Rgb>;

describe('a state tint is a colour a reader can tell apart', () => {
  // 6 is a floor, not a target: today's smallest is the light danger tint at 8.4 (a 10% red on
  // white), and the largest is the dark warn tint at 21.1. What it catches is an alpha dropped
  // low enough that a tinted surface reads as an untinted one.
  const FROM_PANEL = 6;
  // 12 is chosen from the defect: the warn/danger pair this file exists for measured 10.9 and
  // the same pair now measures 14.3, so the floor sits above what shipped and below every pair
  // in either palette (the tightest is ok vs danger in light, at 13.0). Raising it further
  // would start failing pairs nobody has complained about.
  const BETWEEN_STATES = 12;

  for (const theme of THEMES) {
    it(`${theme}: every tint is at least ΔE ${FROM_PANEL} from the panel it sits on`, () => {
      const tints = composited(theme, 'tint');
      const panel = colour(token(theme, '--panel')).rgb;
      for (const state of STATES) {
        const d = deltaE(tints[state], panel);
        expect(d, `${state} tint composites to rgb(${tints[state].map(Math.round)}) on the panel`).toBeGreaterThan(
          FROM_PANEL,
        );
      }
    });

    it(`${theme}: no two states' tints are within ΔE ${BETWEEN_STATES} of each other`, () => {
      const tints = composited(theme, 'tint');
      for (const [a, b] of [
        ['warn', 'danger'],
        ['ok', 'warn'],
        ['ok', 'danger'],
      ]) {
        const d = deltaE(tints[a], tints[b]);
        expect(
          d,
          `${a} composites to rgb(${tints[a].map(Math.round)}) and ${b} to rgb(${tints[b].map(Math.round)})`,
        ).toBeGreaterThan(BETWEEN_STATES);
      }
    });

    it(`${theme}: the warm states' washes still composite warm`, () => {
      // A wash is the quietest thing in the palette, which is exactly why it can invert without
      // anyone noticing. The panel here is blue-shifted (`rgb(31, 36, 42)` — green above red),
      // so a low enough alpha lets the GROUND decide the composite: with #482's base at the
      // 0.09 the old one carried, the warn wash composites to rgb(51, 52, 47), i.e. a green-grey
      // wearing the name of the warm state. The crossing is at alpha 0.116 and the shipped value
      // is 0.14. Red over green, not a hue angle: at 5% saturation an angle is noise.
      for (const state of ['warn', 'danger']) {
        const panel = colour(token(theme, '--panel')).rgb;
        const wash = over(colour(token(theme, `--${state}-wash`)), panel);
        expect(
          wash[0] - wash[1],
          `${state} wash composites to rgb(${wash.map(Math.round)}) over the panel rgb(${panel})`,
        ).toBeGreaterThan(0);
      }
    });
  }

  it('never paints a tint over a copy of itself', () => {
    // Theme-independent, so it runs once: both palettes resolve the same two token NAMES, and
    // what went wrong was the names, not their values.
    //
    // The warning ROW and the `StatusBadge` in its Type cell are two translucent layers of one
    // tone, and until #482 they were the same token: the badge's fill composited over an
    // identical fill and its text measured 5.20:1 in dark — the app's thinnest warn reading, on
    // a stack neither token was designed for, and one that squares every change to the tint's
    // alpha. So the row paints the WASH (a full surface that must not shout) and the badge the
    // TINT (a badge or alert body), which is the division the palette itself declares.
    //
    // Structure here, ratio in `contrast-check.mjs`: that is what measures the stack on the
    // running app, at `.ov-sec .n-data-table-tbody .n-data-table-td`, and it is what caught the
    // 4.10:1 this rule exists to prevent. A check that two names differ cannot say the stack is
    // legible; it can say the two layers are no longer one decision.
    const rowFill = /var\(--[a-z-]+\)/.exec(
      rules.find((r) => r.selectors.includes('.n-data-table-tr.warn > .n-data-table-td'))?.body ?? '',
    );
    expect(rowFill, 'the warn row rule names no token').not.toBeNull();
    expect(
      (rowFill as RegExpExecArray)[0],
      'the warn row and the warn status badge paint the same translucent layer',
    ).not.toBe(TONE_VARS.warn?.color);
  });
});
