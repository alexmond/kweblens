# Column parity corpus

Two files that make "the server computes what the SPA computes" a **measurement** rather than a
claim:

| file | what it is | written by |
|---|---|---|
| `objects.json` | cluster objects, and the CRD printer-column declarations, to render | a human |
| `expected.json` | the string every column produces for every object | **the TypeScript**, mechanically |

`expected.json` is a golden file. Nothing hand-writes it, because two hand-written expectations
that happen to agree prove nothing about the two implementations — they prove the author held one
idea in mind twice.

## Who reads it

- `kweblens-ui/src/columnParity.test.ts` renders `objects.json` through `columns.ts` and asserts
  the result **equals** `expected.json`.
- `kweblens-core`'s `ColumnParityTest` renders the same objects through
  `org.alexmond.kweblens.column` and asserts the result equals the same `expected.json`.

So a change on either side goes red, and the loop closes:

1. change a renderer in `columns.ts` → the TypeScript test fails against the golden;
2. regenerate the golden (below) → the Java test fails against the new golden;
3. change the Java to match → both green.

Skipping step 3 is not possible without deleting a test.

## Coverage is asserted, not assumed

Both tests also check that the golden covers **every** column the implementation defines for a
covered kind, less the `status` column named in `statusColumns` — that one is the server's verdict
already (`ObjectStates.forList`, GH#360) and needs a `StatusContext` opened once per list, so it is
not a per-object function on either side. A column added to `columns.ts` and forgotten here fails
the coverage check, not silently nothing.

## Regenerating the golden

```bash
UPDATE_COLUMN_PARITY=1 npx vitest run src/columnParity.test.ts   # from kweblens-ui/
```

Then read the diff. A golden that changed in a way you did not intend is the finding.
