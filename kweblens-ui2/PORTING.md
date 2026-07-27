# kweblens-ui2 — React → Vue porting conventions

You are porting the React SPA at `kweblens-ui/` to a Vue 3 SPA at `kweblens-ui2/`,
aiming for **behavioural parity**. The module already has: foundation (`src/api.ts`,
`src/types.ts`, `src/auth.ts`, `src/kube.ts`, `src/columns.ts`, `src/format.ts`,
`src/yaml.ts`), the dialog service (`src/dialog.ts`), composables, and shared primitives.
Reuse them — do not re-port them.

## Stack / style

- Vue 3.5 `<script setup lang="ts">`, one component per `.vue` file under `src/components/`.
- Prettier config (already in repo): printWidth 120, singleQuote, semi, trailingComma `all`,
  arrowParens `always`, tabWidth 2. **Run `npx prettier --write` on the files you create.**
- ESLint gates (hard errors): `max-lines-per-function` max 220 (blank/comment-skipped),
  `complexity` 22. If a component's `<script>` logic would exceed these, **split it into
  child SFCs** (that's the idiomatic fix, and matches how the React side was decomposed).
- **No `react` imports, no JSX.** Convert idioms:
  - `useState` → `ref`/`reactive`; `useMemo` → `computed`
  - `useEffect(fn, [deps])` → `watch(() => [deps], fn, { immediate: true })`; mount-only →
    `onMounted`; cleanup `return () => …` → `watch`'s `onCleanup` arg or `onBeforeUnmount`
  - `useRef(domEl)` → template ref (`const x = ref<HTMLElement|null>(null)`)
  - `createPortal(x, document.body)` → `<Teleport to="body">`
  - props → `defineProps<{…}>()`; callback props (`onFoo`) → `defineEmits<{ (e:'foo', …):void }>()`
  - `{cond && <X/>}` → `v-if`; ternary render → `v-if`/`v-else`; `.map` → `v-for` with `:key`
  - `className` → `class`/`:class`; `onClick` → `@click`; controlled input → `v-model`

## Imports available

- `import { api, ApiError, clusterBase } from '../api'` — the full JSON API client (unchanged).
- `import type { … } from '../types'` — all DTOs (ClusterInfo, KubeObject, HelmRelease, EventSummary, PortForward, DockKind, …).
- `import { objName, objNs, objKey, objSpec, objStatus, containerNames, initials, gib, parseCpuCores, parseMemBytes, ageToSeconds, stripManagedFields, objectPorts, toNum } from '../kube'`
- `import { age, statusTone, readyTone, columnsFor } from '../columns'`
- `import { fmtValue, fmtClock, fmtStamp } from '../format'`
- `import { useDialog } from '../dialog'` → `const dialog = useDialog();`
  `await dialog.confirm({ message, title?, confirmLabel?, danger? })` → `boolean`;
  `await dialog.prompt({ message, label?, initial?, placeholder?, type?, confirmLabel? })` → `string | null`.
- `import { auth } from '../auth'` — in-memory HTTP Basic creds (`auth.set/clear/isSet/header`).
- Composables: `import { useTableSort } from '../composables/useTableSort'`
  (`useTableSort(() => rows, initialKey, (row,key)=>string|number)` → `{ sorted /*computed*/, sort /*ref*/, clickHeader }`),
  `useEscapeKey(fn)`, `import { useMenuDismiss } from '../composables/useMenuDismiss'`.

## Shared primitive SFCs (`src/components/`) — props

- `YamlView.vue` — `{ text: string }`
- `UsageBar.vue` — `{ fraction: number; color: string; text: string }`
- `StatusBadge.vue` — `{ text: string }`
- `Chips.vue` — `{ map: Record<string,string> }`
- `Accordion.vue` — `{ title: string; count?: number; defaultOpen?: boolean }` + default slot = body
- `SecretData.vue` — `{ data: Record<string,string> }`
- `ContainerSquares.vue` — `{ obj: KubeObject }`
- `SortTh.vue` — `{ label: string; colKey: string; sort: SortState }`, emits `('sort', key)`
- `MetricChart.vue` — `{ cluster: string; target: string; namespace?: string; name?: string; label: string }`

## CSS

`src/styles.css` was copied **verbatim** from the React app. **Reuse the exact same class
names** the React source uses for every element — do not invent or rename classes. The
styling already exists; matching class names is what makes the Vue view look identical.

## Cross-cutting actions

Do not reach for global app state. For actions that belong to the shell (navigate to another
kind, open a terminal/log dock, sign-in required, auth expired), **emit a Vue event** and list
every event you emit in a comment block at the top of the component. The shell (`App.vue`) will
wire them. Match the React callback props' payloads.

## Output

Write the finished `.vue`/`.ts` files to disk at the paths given in your task. Keep each file
focused; create child SFCs freely. Return a concise list of: files written, the events each
root component emits (with payload types), and any deviation from the React behaviour.
