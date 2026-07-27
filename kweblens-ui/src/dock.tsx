import type { PointerEvent as ReactPointerEvent } from 'react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';

import { clusterBase } from './api';
import type { DockKind } from './types';

export type DockSession = {
  id: string;
  kind: DockKind;
  namespace: string;
  pod: string;
  containers: string[];
  /** Popped out of the dock into a floating window. */
  floating?: boolean;
  /** Floating window geometry (viewport px). */
  rect?: { x: number; y: number; w: number; h: number };
  /** Terminal only: attach to the running process (kubectl attach) instead of a new shell. */
  attach?: boolean;
};

/**
 * Owns all terminal + log sessions. Docked sessions appear as tabs in a resizable bottom
 * panel; a session can be popped out into a floating, draggable/resizable window and folded
 * back. Each session's body is rendered into a STABLE detached DOM node and that node is
 * moved (appendChild) between the dock and its floating frame — never re-created — so the
 * shell/WebSocket/log stream survives detach and re-dock.
 */
export function DockArea(props: {
  cluster: string;
  sessions: DockSession[];
  active: string | null;
  onActivate: (id: string) => void;
  onClose: (id: string) => void;
  onToggleFloat: (id: string, floating: boolean) => void;
}) {
  const { cluster, sessions, active, onActivate, onClose, onToggleFloat } = props;
  const [height, setHeight] = useState(300);
  const [minimized, setMinimized] = useState(false);
  const [dockBodyEl, setDockBodyEl] = useState<HTMLDivElement | null>(null);
  const draggingRef = useRef(false);

  useEffect(() => {
    const move = (e: PointerEvent) => {
      if (!draggingRef.current) {
        return;
      }
      const h = window.innerHeight - e.clientY;
      // Leave room for the brand bar + some content above the dock.
      setHeight(Math.min(Math.max(h, 120), window.innerHeight - 180));
    };
    const up = () => {
      draggingRef.current = false;
      document.body.classList.remove('row-resizing');
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    return () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
  }, []);

  const docked = sessions.filter((s) => !s.floating);
  const activeId = docked.some((s) => s.id === active) ? active : (docked[docked.length - 1]?.id ?? null);

  return (
    <>
      {sessions.map((s) => (
        <SessionWindow
          key={s.id}
          cluster={cluster}
          session={s}
          active={s.id === activeId}
          dockBodyEl={dockBodyEl}
          onDock={() => onToggleFloat(s.id, false)}
          onClose={() => onClose(s.id)}
        />
      ))}
      {docked.length > 0 && (
        <div className={'dock-panel' + (minimized ? ' minimized' : '')} style={minimized ? {} : { height }}>
          <div
            className="dock-resize"
            title="Drag to resize"
            onPointerDown={() => {
              draggingRef.current = true;
              document.body.classList.add('row-resizing');
            }}
          />
          <div className="dock-tabs">
            {docked.map((s) => (
              <div
                key={s.id}
                className={'dock-tab' + (s.id === activeId ? ' active' : '')}
                onClick={() => onActivate(s.id)}
              >
                <i className={'term-dot ' + s.kind} />
                <span className="dock-tab-label">
                  {s.kind === 'logs' ? 'logs' : 'sh'} · {s.namespace}/{s.pod}
                </span>
                <button
                  className="dock-tab-btn"
                  title="Open in a floating window"
                  onClick={(e) => {
                    e.stopPropagation();
                    onToggleFloat(s.id, true);
                  }}
                >
                  ⧉
                </button>
                <button
                  className="dock-tab-close"
                  title="Close"
                  onClick={(e) => {
                    e.stopPropagation();
                    onClose(s.id);
                  }}
                >
                  ×
                </button>
              </div>
            ))}
            <span className="dock-tab-spacer" />
            <button
              className="dock-min"
              title={minimized ? 'Expand' : 'Minimize'}
              onClick={() => setMinimized((m) => !m)}
            >
              {minimized ? '▴' : '—'}
            </button>
          </div>
          <div className="dock-bodies" ref={setDockBodyEl} />
        </div>
      )}
    </>
  );
}

function SessionWindow(props: {
  cluster: string;
  session: DockSession;
  active: boolean;
  dockBodyEl: HTMLDivElement | null;
  onDock: () => void;
  onClose: () => void;
}) {
  const { cluster, session, active, dockBodyEl, onDock, onClose } = props;
  // A stable node the body renders into; relocated (not re-created) between dock and float.
  const hostEl = useMemo(() => {
    const el = document.createElement('div');
    el.className = 'session-host';
    return el;
  }, []);
  const [floatEl, setFloatEl] = useState<HTMLDivElement | null>(null);

  useEffect(() => {
    const target = session.floating ? floatEl : dockBodyEl;
    if (target && hostEl.parentElement !== target) {
      target.appendChild(hostEl);
    }
    // Docked, inactive tabs are hidden; floating and active-docked are shown.
    hostEl.style.display = session.floating || active ? 'flex' : 'none';
  }, [session.floating, active, dockBodyEl, floatEl, hostEl]);

  const body =
    session.kind === 'terminal' ? (
      <TerminalSession cluster={cluster} session={session} />
    ) : (
      <LogsSession cluster={cluster} session={session} />
    );

  return (
    <>
      {createPortal(body, hostEl)}
      {session.floating && (
        <FloatingFrame session={session} onDock={onDock} onClose={onClose} setContentEl={setFloatEl} />
      )}
    </>
  );
}

function FloatingFrame(props: {
  session: DockSession;
  onDock: () => void;
  onClose: () => void;
  setContentEl: (el: HTMLDivElement | null) => void;
}) {
  const { session, onDock, onClose, setContentEl } = props;
  const [rect, setRect] = useState(session.rect ?? { x: 140, y: 140, w: 640, h: 340 });
  const [collapsed, setCollapsed] = useState(false);
  const dragRef = useRef<{
    resize: boolean;
    sx: number;
    sy: number;
    ox: number;
    oy: number;
    ow: number;
    oh: number;
  } | null>(null);

  useEffect(() => {
    const move = (e: PointerEvent) => {
      const d = dragRef.current;
      if (!d) {
        return;
      }
      const dx = e.clientX - d.sx;
      const dy = e.clientY - d.sy;
      if (d.resize) {
        setRect((r) => ({ ...r, w: Math.max(280, d.ow + dx), h: Math.max(160, d.oh + dy) }));
      } else {
        setRect((r) => ({ ...r, x: Math.max(0, d.ox + dx), y: Math.max(0, d.oy + dy) }));
      }
    };
    const up = () => {
      dragRef.current = null;
      document.body.classList.remove('dragging');
    };
    window.addEventListener('pointermove', move);
    window.addEventListener('pointerup', up);
    return () => {
      window.removeEventListener('pointermove', move);
      window.removeEventListener('pointerup', up);
    };
  }, []);

  const start = (resize: boolean) => (e: ReactPointerEvent<HTMLDivElement>) => {
    e.stopPropagation();
    dragRef.current = { resize, sx: e.clientX, sy: e.clientY, ox: rect.x, oy: rect.y, ow: rect.w, oh: rect.h };
    document.body.classList.add('dragging');
  };

  return (
    <div
      className={'float-window' + (collapsed ? ' collapsed' : '')}
      style={{ left: rect.x, top: rect.y, width: rect.w, height: collapsed ? 'auto' : rect.h }}
    >
      <div className="float-head" onPointerDown={collapsed ? undefined : start(false)}>
        <span className="float-title">
          <i className={'term-dot ' + session.kind} /> {session.kind === 'logs' ? 'logs' : 'sh'} · {session.namespace}/
          {session.pod}
        </span>
        <span className="float-actions">
          <button
            className="float-btn"
            title={collapsed ? 'Expand' : 'Minimize'}
            onClick={() => setCollapsed((c) => !c)}
          >
            {collapsed ? '▢' : '—'}
          </button>
          <button className="float-btn" title="Dock back" onClick={onDock}>
            ⧉
          </button>
          <button className="float-btn" title="Close" onClick={onClose}>
            ×
          </button>
        </span>
      </div>
      <div className="float-body" ref={setContentEl} />
      <div className="float-resize" onPointerDown={start(true)} title="Resize" />
    </div>
  );
}

function TerminalSession(props: { cluster: string; session: DockSession }) {
  const { cluster, session } = props;
  const { namespace, pod, containers } = session;
  const ref = useRef<HTMLDivElement>(null);
  const fitRef = useRef<{ fit: () => void } | null>(null);
  const [container, setContainer] = useState(containers[0] ?? '');

  useEffect(() => {
    let cleanup = () => undefined as void;
    let cancelled = false;
    const enc = encodeURIComponent;
    (async () => {
      const [{ Terminal }, { FitAddon }] = await Promise.all([import('@xterm/xterm'), import('@xterm/addon-fit')]);
      if (cancelled || !ref.current) {
        return;
      }
      const term = new Terminal({ fontSize: 13, cursorBlink: true, theme: { background: '#0f172a' } });
      const fit = new FitAddon();
      term.loadAddon(fit);
      term.open(ref.current);
      fit.fit();
      fitRef.current = fit;
      const proto = window.location.protocol === 'https:' ? 'wss' : 'ws';
      const mode = session.attach ? '&mode=attach' : '';
      const url = `${proto}://${window.location.host}/ws/exec?cluster=${enc(cluster)}&namespace=${enc(namespace)}&pod=${enc(pod)}&container=${enc(container)}${mode}`;
      const ws = new WebSocket(url);
      ws.onmessage = (e) => {
        if (typeof e.data === 'string') {
          term.write(e.data);
        }
      };
      ws.onclose = () => term.write('\r\n\x1b[90m[session closed]\x1b[0m\r\n');
      ws.onerror = () => term.write('\r\n\x1b[31m[connection error]\x1b[0m\r\n');
      term.onData((d) => {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(d);
        }
      });
      cleanup = () => {
        fitRef.current = null;
        ws.close();
        term.dispose();
      };
    })();
    return () => {
      cancelled = true;
      cleanup();
    };
  }, [cluster, namespace, pod, container]);

  // Refit whenever the terminal body changes size — tab shown/hidden, dock resize, float
  // move/resize, or window resize (xterm can't measure a hidden or stale-sized element).
  useEffect(() => {
    const el = ref.current;
    if (!el) {
      return;
    }
    const ro = new ResizeObserver(() => {
      try {
        fitRef.current?.fit();
      } catch {
        // not ready to fit yet
      }
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  return (
    <div className="dock-session">
      {containers.length > 1 && (
        <div className="dock-toolbar">
          <select className="dock-select" value={container} onChange={(e) => setContainer(e.target.value)}>
            {containers.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        </div>
      )}
      <div className="term-body" ref={ref} />
    </div>
  );
}

function LogsSession(props: { cluster: string; session: DockSession }) {
  const { cluster, session } = props;
  const { namespace, pod, containers } = session;
  const [container, setContainer] = useState(containers[0] ?? '');
  const [lines, setLines] = useState<string[]>([]);
  const [wrap, setWrap] = useState(false);
  const bodyRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let cancelled = false;
    setLines([]);
    const enc = encodeURIComponent;
    const base = `${clusterBase(cluster)}/pods/${enc(namespace)}/${enc(pod)}/log`;
    const cq = container ? `container=${enc(container)}&` : '';
    // Tail snapshot, then follow via SSE.
    fetch(`${base}?${cq}tailLines=500`)
      .then((r) => r.text())
      .then((t) => {
        if (!cancelled) {
          setLines(t ? t.replace(/\n$/, '').split('\n') : []);
        }
      })
      .catch(() => undefined);
    const es = new EventSource(`${base}/stream?${cq}`);
    es.onmessage = (e) => !cancelled && setLines((prev) => [...prev, e.data].slice(-5000));
    return () => {
      cancelled = true;
      es.close();
    };
  }, [cluster, namespace, pod, container]);

  useEffect(() => {
    const el = bodyRef.current;
    if (el) {
      el.scrollTop = el.scrollHeight;
    }
  }, [lines]);

  return (
    <div className="dock-session">
      <div className="dock-toolbar">
        {containers.length > 1 && (
          <select className="dock-select" value={container} onChange={(e) => setContainer(e.target.value)}>
            {containers.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
        )}
        <label className="dock-toggle">
          <input type="checkbox" checked={wrap} onChange={(e) => setWrap(e.target.checked)} /> wrap
        </label>
      </div>
      <div className={'term-body log-body' + (wrap ? ' wrap' : '')} ref={bodyRef}>
        {lines.length === 0 ? <div className="log-line dim">(no output yet)</div> : null}
        {lines.map((l, i) => (
          <div className="log-line" key={i}>
            {l}
          </div>
        ))}
      </div>
    </div>
  );
}
