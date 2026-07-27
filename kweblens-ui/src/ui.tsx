import type { ReactNode } from 'react';
import { useEffect, useState } from 'react';
import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';

import { api } from './api';
import { statusTone } from './columns';
import type { SortState } from './hooks';
import type { KubeObject, MetricPoint, MetricSeries } from './types';

// Shared, low-level display primitives used across the dashboard's feature views.

// Split one YAML line into coloured tokens (indent · list-dash · key · value/comment).
function yamlTokens(line: string): ReactNode[] {
  // Runs on a single, bounded YAML line (non-global, anchored) — no ReDoS in practice.
  // eslint-disable-next-line sonarjs/super-linear-regex
  const m = /^(\s*)(-\s+)?(?:([\w.\-/]+)(:))?(\s*)(.*)$/.exec(line);
  if (!m) {
    return [line];
  }
  const [, indent, dash, key, colon, gap, rest] = m;
  const nodes: ReactNode[] = [indent];
  if (dash) {
    nodes.push(
      <span className="yk-dash" key="d">
        {dash}
      </span>,
    );
  }
  if (key) {
    nodes.push(
      <span className="yk-key" key="k">
        {key}
      </span>,
      colon,
    );
  }
  nodes.push(gap);
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
    nodes.push(
      <span className={cls} key="v">
        {rest}
      </span>,
    );
  }
  return nodes;
}

export function YamlView(props: { text: string }) {
  return (
    <pre className="yaml">
      {props.text.split('\n').map((line, i) => (
        <span key={i}>
          {yamlTokens(line)}
          {'\n'}
        </span>
      ))}
    </pre>
  );
}

// One coloured square per container, by state (Freelens-style), with a hover tooltip.
function containerSquare(cs: Record<string, unknown>): { tone: string; title: string } {
  const name = String(cs.name ?? '');
  const state = (cs.state as Record<string, Record<string, unknown>>) ?? {};
  const ready = Boolean(cs.ready);
  const restarts = Number(cs.restartCount ?? 0);
  let tone = 'wait';
  const lines = [name];
  if (state.running) {
    tone = ready ? 'ok' : 'warn';
    lines.push(ready ? 'Running' : 'Running (not ready)');
    if (state.running.startedAt) {
      lines.push('Started At  ' + String(state.running.startedAt));
    }
  } else if (state.terminated) {
    const t = state.terminated;
    tone = t.exitCode === 0 ? 'done' : 'err';
    lines.push(`Terminated · ${String(t.reason ?? '')} (exit ${Number(t.exitCode ?? 0)})`);
  } else if (state.waiting) {
    const reason = String(state.waiting.reason ?? 'Waiting');
    tone = /crashloop|imagepull|errimage|error|invalid/i.test(reason) ? 'err' : 'wait';
    lines.push('Waiting · ' + reason);
  }
  if (restarts > 0) {
    lines.push('Restarts: ' + restarts);
  }
  return { tone, title: lines.join('\n') };
}

export function ContainerSquares(props: { obj: KubeObject }) {
  const { obj } = props;
  const statuses = ((obj.status as Record<string, unknown>)?.containerStatuses as Record<string, unknown>[]) ?? [];
  const specContainers = ((obj.spec as Record<string, unknown>)?.containers as Record<string, unknown>[]) ?? [];
  const list = statuses.length > 0 ? statuses : specContainers.map((c) => ({ name: c.name, ready: false, state: {} }));
  if (list.length === 0) {
    return <>—</>;
  }
  return (
    <span className="csquares">
      {list.map((cs, i) => {
        const { tone, title } = containerSquare(cs);
        return <span key={String(cs.name ?? i)} className={'csq csq-' + tone} title={title} />;
      })}
    </span>
  );
}

// A status/phase value coloured green/amber/red by health; plain text when unrecognised.
export function StatusBadge(props: { text: string }) {
  const { text } = props;
  const tone = statusTone(text);
  if (!tone) {
    return <>{text}</>;
  }
  return <span className={'status-pill status-' + tone}>{text}</span>;
}

// A used/total value with a proportional fill bar (vCenter/Freelens style).
export function UsageBar(props: { fraction: number; color: string; text: string }) {
  const { fraction, color, text } = props;
  const pct = Math.max(0, Math.min(100, fraction * 100));
  return (
    <div className="ubar" title={`${Math.round(pct)}%`}>
      <div className="ubar-track">
        <div className="ubar-fill" style={{ width: pct + '%', background: color }} />
      </div>
      <div className="ubar-text">{text}</div>
    </div>
  );
}

/** Collapsible section — a Freelens-style accordion. */
export function Accordion(props: { title: string; count?: number; defaultOpen?: boolean; children: ReactNode }) {
  const [open, setOpen] = useState(props.defaultOpen ?? true);
  return (
    <section className="ov-sec acc">
      <h3 className="acc-head" onClick={() => setOpen((o) => !o)}>
        <span className={'acc-caret' + (open ? ' open' : '')}>▸</span>
        {props.title}
        {props.count !== undefined && <span className="acc-count">{props.count}</span>}
      </h3>
      {open && <div className="acc-body">{props.children}</div>}
    </section>
  );
}

/** Secret data table — base64 values are masked until per-key Reveal decodes them. */
export function SecretData(props: { data: Record<string, string> }) {
  const [revealed, setRevealed] = useState<Record<string, boolean>>({});
  const decode = (v: string) => {
    try {
      return atob(v);
    } catch {
      return '‹binary›';
    }
  };
  return (
    <table className="mini">
      <thead>
        <tr>
          <th>Key</th>
          <th>Value</th>
          <th />
        </tr>
      </thead>
      <tbody>
        {Object.keys(props.data).map((k) => {
          const shown = revealed[k];
          return (
            <tr key={k}>
              <td className="mono">{k}</td>
              <td className="mono">{shown ? decode(props.data[k]) : '••••••••'}</td>
              <td>
                <button className="linkbtn" onClick={() => setRevealed((r) => ({ ...r, [k]: !r[k] }))}>
                  {shown ? 'Hide' : 'Reveal'}
                </button>
              </td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}

export function chipsOf(map: Record<string, string>) {
  return (
    <div className="chips">
      {Object.entries(map).map(([k, v]) => (
        <span className="chip" key={k}>
          {k}={v}
        </span>
      ))}
    </div>
  );
}

function fmtValue(unit: string, v: number): string {
  if (unit === 'bytes') {
    return Math.round(v / 1048576) + 'Mi';
  }
  if (unit === 'cores') {
    return v < 1 ? Math.round(v * 1000) + 'm' : v.toFixed(2);
  }
  return String(Math.round(v));
}

// t is epoch SECONDS (Prometheus range query) — ×1000 for the JS Date.
const fmtClock = (t: number): string =>
  new Date(t * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
const fmtStamp = (t: number): string => new Date(t * 1000).toLocaleString();

// Hover tooltip: full timestamp + formatted value for the point under the cursor.
function ChartTooltip(props: { unit: string; active?: boolean; payload?: { value: number }[]; label?: number }) {
  const { unit, active, payload, label } = props;
  if (!active || !payload || payload.length === 0) {
    return null;
  }
  return (
    <div className="chart-tip">
      <div className="chart-tip-t">{fmtStamp(label ?? 0)}</div>
      <div className="chart-tip-v">{fmtValue(unit, payload[0].value)}</div>
    </div>
  );
}

// Time (x) / value (y) area chart with axes, hover tooltip, and click-to-select a point.
function MetricGraph(props: { series: MetricSeries; onSelect: (p: MetricPoint | null) => void }) {
  const { series, onSelect } = props;
  const accent = 'var(--accent)';
  const pick = (state: { activePayload?: { payload: MetricPoint }[] }) => {
    onSelect(state?.activePayload?.[0]?.payload ?? null);
  };
  return (
    <ResponsiveContainer width="100%" height={150}>
      <AreaChart data={series.points} margin={{ top: 6, right: 10, bottom: 0, left: 0 }} onClick={pick}>
        <defs>
          <linearGradient id="metric-fill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={accent} stopOpacity={0.28} />
            <stop offset="100%" stopColor={accent} stopOpacity={0.02} />
          </linearGradient>
        </defs>
        <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
        <XAxis
          dataKey="t"
          type="number"
          domain={['dataMin', 'dataMax']}
          tickFormatter={fmtClock}
          tick={{ fontSize: 10, fill: 'var(--muted)' }}
          stroke="var(--border)"
          minTickGap={44}
        />
        <YAxis
          width={46}
          tickFormatter={(v: number) => fmtValue(series.unit, v)}
          tick={{ fontSize: 10, fill: 'var(--muted)' }}
          stroke="var(--border)"
        />
        <Tooltip content={<ChartTooltip unit={series.unit} />} />
        <Area
          type="monotone"
          dataKey="v"
          stroke={accent}
          strokeWidth={1.5}
          fill="url(#metric-fill)"
          activeDot={{ r: 4, stroke: accent, strokeWidth: 2, fill: 'var(--panel)' }}
          isAnimationActive={false}
        />
      </AreaChart>
    </ResponsiveContainer>
  );
}

function ChartBody(props: { series: MetricSeries | null }) {
  const { series } = props;
  const [selected, setSelected] = useState<MetricPoint | null>(null);
  useEffect(() => setSelected(null), [series]);
  if (series === null) {
    return <div className="empty">Loading…</div>;
  }
  if (!series.available) {
    return <div className="empty">Graphs need a Prometheus / VictoriaMetrics backend.</div>;
  }
  if (series.points.length === 0) {
    return <div className="empty">No data.</div>;
  }
  const vals = series.points.map((p) => p.v);
  const max = Math.max(...vals);
  return (
    <div className="spark">
      <MetricGraph series={series} onSelect={setSelected} />
      <div className="spark-meta">
        {selected ? (
          <span className="chart-sel">
            selected {fmtStamp(selected.t)} · <strong>{fmtValue(series.unit, selected.v)}</strong>
            <button className="chart-sel-clear" onClick={() => setSelected(null)} aria-label="Clear selection">
              ×
            </button>
          </span>
        ) : (
          <>
            now {fmtValue(series.unit, vals[vals.length - 1])} · peak {fmtValue(series.unit, max)} · click a point to
            inspect
          </>
        )}
      </div>
    </div>
  );
}

export function MetricChart(props: {
  cluster: string;
  target: string;
  namespace?: string;
  name?: string;
  label: string;
}) {
  const { cluster, target, namespace, name, label } = props;
  const [series, setSeries] = useState<MetricSeries | null>(null);
  useEffect(() => {
    let cancelled = false;
    setSeries(null);
    api
      .metricGraph(cluster, target, { namespace, name, minutes: 60 })
      .then((s) => !cancelled && setSeries(s))
      .catch(() => !cancelled && setSeries({ available: false, unit: '', points: [] }));
    return () => {
      cancelled = true;
    };
  }, [cluster, target, namespace, name]);
  return (
    <div className="chart">
      <div className="chart-title">{label}</div>
      <ChartBody series={series} />
    </div>
  );
}

export function SortTh(props: { label: string; colKey: string; sort: SortState; onClick: (key: string) => void }) {
  const { label, colKey, sort, onClick } = props;
  return (
    <th className="sortable" onClick={() => onClick(colKey)}>
      {label}
      {sort.key === colKey && <span className="sort-ind">{sort.dir === 1 ? ' ▲' : ' ▼'}</span>}
    </th>
  );
}
