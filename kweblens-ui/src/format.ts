// Display formatters shared across the dashboard (metric values, chart axis ticks).
// Pure functions, no Vue — mirrors the helpers that lived in the React ui.tsx.

export function fmtValue(unit: string, v: number): string {
  if (unit === 'bytes') {
    return Math.round(v / 1048576) + 'Mi';
  }
  if (unit === 'cores') {
    return v < 1 ? Math.round(v * 1000) + 'm' : v.toFixed(2);
  }
  return String(Math.round(v));
}

// t is epoch SECONDS (Prometheus range query) — ×1000 for the JS Date.
export const fmtStamp = (t: number): string => new Date(t * 1000).toLocaleString();
