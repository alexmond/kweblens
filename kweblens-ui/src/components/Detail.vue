<script setup lang="ts">
// The slide-in resource Detail drawer: NDrawer + NDrawerContent, a NTabs bar
// (Overview / YAML / Events, plus Metrics for Pods) and Escape-to-close. Lazy-loads
// events when its tab is first shown.
//
// Emits (mirror the React callback props' payloads):
//   navigate     (kind: string, ns?: string)         — open another kind/object list
//   helm-release (namespace: string, name: string)   — open a Helm release's resources
//   auth-expired ()                                   — a YAML apply came back 401/403
//   require-auth ()                                   — a pane needs the login (Files)
//   close        ()                                   — close the drawer (X or Escape)
import { NDrawer, NDrawerContent, NDropdown, NTabPane, NTabs } from 'naive-ui';
import { shallowRef, computed, ref, watch } from 'vue';

import { api } from '../api';
import { failureNotice } from '../apiFailure';
import { useEscapeKey } from '../composables/useEscapeKey';
import { needsFullObject, objName, objNs } from '../kube';
import { filesFeature } from '../podFiles';
import { controlAccess } from '../permissions';
import type { RowAction, RowActionDef } from '../rowActions';
import { parseRowActionKey } from '../rowActions';
import type { EventSummary, KindAccess, KubeObject } from '../types';
import { naiveActionOptions } from './actionMenu';
import { drawerActions, drawerBadges } from './drawerHeader';
import EventsPane from './EventsPane.vue';
import MetricChart from './MetricChart.vue';
import NodePodsPane from './NodePodsPane.vue';
import Overview from './Overview.vue';
import PodFilesPane from './PodFilesPane.vue';
import YamlTab from './YamlTab.vue';

const props = defineProps<{
  cluster: string;
  resourceId: string;
  obj: KubeObject;
  initialEdit: boolean;
  authed: boolean;
  /**
   * What the deployment's service account may do with THIS kind here, or null when it is not
   * known — which leaves every action enabled (#354). The shell only passes it when the drawer
   * is showing the kind the verdicts are about.
   */
  access?: KindAccess | null;
}>();
const emit = defineEmits<{
  (e: 'navigate', kind: string, ns?: string): void;
  (e: 'helm-release', namespace: string, name: string): void;
  (e: 'auth-expired'): void;
  (e: 'require-auth'): void;
  (e: 'open-object', obj: KubeObject): void;
  (e: 'row-action', action: RowAction, obj: KubeObject, container?: string): void;
  (e: 'close'): void;
}>();

type Tab = 'overview' | 'pods' | 'yaml' | 'events' | 'metrics' | 'files';

// Whether to offer the Files tab, decided ONCE when this drawer opens and deliberately not
// reactive. The pod file browser is off by default and there is no endpoint that reports
// it, so the first listing is what discovers it (see PodFilesPane). If that answer is
// "disabled", this drawer keeps the tab — the explanation the reader just asked for is on
// it — and every drawer opened afterwards omits it, so no tab sits there able only to 403.
const showFiles = filesFeature.state.value !== 'disabled';
const tab = ref<Tab>(props.initialEdit ? 'yaml' : 'overview');
const events = shallowRef<EventSummary[] | null>(null);
const eventsError = ref<string | null>(null);

const kind = computed(() => props.obj.kind ?? '');
const isNode = computed(() => kind.value === 'Node');
const name = computed(() => objName(props.obj));
const ns = computed(() => objNs(props.obj) ?? '');

// ---- The whole object, when the row is not enough (GH#276) ----
// List payloads ship ConfigMap/Secret keys without their values, so a drawer opened from such
// a row would render a Data section of dashes. `needsFullObject` reads that off the row itself
// rather than off a kind list, so only the rows that were actually stripped pay for a request:
// a Pod, Deployment or Service drawer makes none, and opens exactly as fast as before.
//
// The fetch does not block: the drawer renders from the row immediately and swaps in the full
// object when it lands. A failure is deliberately silent and leaves the row in place — every
// other section is complete, and the Data cells already say "—" rather than claiming a value.
// The parent keys this component on resourceId + object, so it remounts per object and this
// runs once per drawer.
const full = shallowRef<KubeObject | null>(null);
if (needsFullObject(props.obj)) {
  api
    .object(props.cluster, props.resourceId, name.value, ns.value || undefined)
    .then((o) => (full.value = o))
    .catch(() => undefined);
}
const view = computed<KubeObject>(() => full.value ?? props.obj);

// ---- Header, laid out for the width it has (#233) ----
// Both decisions come from `drawerHeader.ts`; this component only renders them and lets the
// container query choose. The menu is the FULL action list at every width — the buttons are
// a shortcut to three of them when there is room, never the only way to reach one.
const badges = computed(() => drawerBadges(view.value));
const actions = computed(() => drawerActions(view.value, props.access));
const menuOptions = computed(() => naiveActionOptions(actions.value.menu));
// A promoted button gets the same verdict its menu entry does — a Restart the cluster has
// already said no to is a button that can only 403. The toolbar has no room for a sentence,
// so the button carries the reason as its tooltip and the MENU beside it (which is present at
// every width, and shows the same action) carries it in full. The explanation is never only a
// tooltip: that is the point of the menu being the complete list.
const buttonAccess = (a: RowActionDef) => controlAccess(props.access, a.verb);
const onMenu = (key: string) => {
  const { action, container } = parseRowActionKey(key);
  emit('row-action', action, view.value, container);
};

// Expand-to-fill: the drawer is mounted into the content column (see `to` below), so 100%
// is exactly the area the table occupies — no header/footer/sidebar overlap either way.
// `width` tracks the user's own resizing so collapsing restores their chosen width.
const expanded = ref(false);
const width = ref(520);
const drawerWidth = computed<number | string>(() => (expanded.value ? '100%' : width.value));

// The parent mounts Detail via v-if; keep the drawer shown while mounted and route any
// close (the ✕ or Escape) to the `close` emit so the parent tears it down.
const show = ref(true);
// True while the YAML tab's pop-out editor is open. Escape closes this drawer, and the
// editor is a separate overlay with its own Escape — so while it is open the drawer's close
// is suppressed and Escape belongs to the editor, not to the panel underneath it.
const yamlEditing = ref(false);
const onShow = (v: boolean) => {
  if (!v && !yamlEditing.value) {
    emit('close');
  }
};

useEscapeKey(() => {
  if (!yamlEditing.value) {
    emit('close');
  }
});

// Fetched once, when the Events tab is first shown. `loadEvents` is also what the pane's
// Retry calls, which is why the "already tried" guard is the caller's and not part of it: a
// retry is exactly the case where the previous error must NOT stop the request.
let eventReq = 0;
const loadEvents = () => {
  const my = ++eventReq;
  eventsError.value = null;
  api
    .objectEvents(props.cluster, kind.value, name.value, ns.value || undefined)
    .then((e) => my === eventReq && (events.value = e))
    .catch((e) => my === eventReq && (eventsError.value = failureNotice(e)));
};

watch(
  () => tab.value,
  () => {
    if (tab.value === 'events' && events.value === null && eventsError.value === null) {
      loadEvents();
    }
  },
  { immediate: true },
);
</script>

<template>
  <!-- A NON-MODAL panel is not dismissed by using the page behind it (GH#480), which is what
       `mask-closable` is doing here even though there is no mask to click. With `show-mask`
       false naive registers evtd's `clickoutside` trap — a mousedown AND a mouseup outside
       the drawer body — and routes it into the very handler the mask click uses, whose only
       gate is `mask-closable`, which DEFAULTS TO TRUE. So every pointer press behind the
       drawer closed it and dropped the selection with it (`row-active` went with `detail`):
       the theme toggle is the click that got measured, but the footer and the brand bar did
       it too, with no theme change anywhere near them. The ✕ and Escape still close the
       drawer, and `scripts/drawer-persist-check.mjs` asserts that half as well — "never
       closes" would pass a survival check perfectly. -->
  <NDrawer
    :show="show"
    :resizable="!expanded"
    :width="drawerWidth"
    :min-width="360"
    :max-width="1400"
    placement="right"
    to=".content-col"
    :show-mask="false"
    :trap-focus="false"
    :block-scroll="false"
    :aria-label="`${kind} ${name}`"
    :mask-closable="false"
    @update:show="onShow"
    @update:width="(w) => (width = w)"
  >
    <NDrawerContent closable body-content-style="padding: 0 20px; display: flex; flex-direction: column;">
      <!-- The header sits OUTSIDE the tab panes, so it needs its own `kw-pane` to have a
           width to query (#233); the wrapper is the container because a container query
           never styles the container itself, and `.drawer-title` is what has to restyle. -->
      <template #header>
        <div class="drawer-head-pane kw-pane">
          <div class="drawer-title">
            <!-- Narrow keeps the kind inline before the name; wide shows it as the first of
                 the badges below, which is why both exist rather than one moving. -->
            <span class="drawer-kind kw-when-narrow" :title="kind">{{ kind }}</span>
            <span class="drawer-name" :title="name">{{ name }}</span>
            <button
              type="button"
              class="drawer-expand"
              :title="expanded ? 'Restore panel width' : 'Expand to fill'"
              :aria-label="expanded ? 'Restore panel width' : 'Expand to fill'"
              @click="expanded = !expanded"
            >
              {{ expanded ? '⤡' : '⤢' }}
            </button>
            <div class="drawer-badges kw-when-wide">
              <span v-for="b in badges" :key="b.key" :class="`drawer-badge tone-${b.tone}`" :title="b.title">
                {{ b.text }}
              </span>
            </div>
          </div>
        </div>
      </template>

      <!-- `kw-pane` makes each tab pane a container-query container (#231), so what a pane
           renders can respond to the width the USER gave the drawer — 520px or expanded —
           which no viewport media query can see. The pane, not its content, is the container:
           a container query never styles the container itself, so the content is free to
           become the two-column layout. -->
      <NTabs v-model:value="tab" type="line" size="small" class="drawer-tabs kw-pane" pane-class="drawer-body kw-pane">
        <!-- The band to the right of the tab row was empty at every width. It now carries the
             object's actions: three buttons once the pane is wide enough for them, and a menu
             holding the COMPLETE list at both widths, so nothing is reachable at one width
             and not the other. -->
        <template #suffix>
          <div class="drawer-actions">
            <button
              v-for="a in actions.buttons"
              :key="a.id"
              type="button"
              class="drawer-action kw-when-wide"
              :disabled="buttonAccess(a).disabled"
              :title="buttonAccess(a).reason ?? undefined"
              @click="emit('row-action', a.id, view)"
            >
              {{ a.label }}
            </button>
            <NDropdown trigger="click" :options="menuOptions" @select="onMenu">
              <button type="button" class="drawer-action drawer-action-menu" title="Actions" aria-label="Actions">
                ⋮
              </button>
            </NDropdown>
          </div>
        </template>
        <NTabPane name="overview" tab="Overview" display-directive="if">
          <Overview
            :obj="view"
            :cluster="cluster"
            :resource-id="resourceId"
            @navigate="(k, n) => emit('navigate', k, n)"
            @helm-release="(nsp, nm) => emit('helm-release', nsp, nm)"
          />
        </NTabPane>
        <NTabPane v-if="isNode" name="pods" tab="Pods" display-directive="if">
          <NodePodsPane :cluster="cluster" :node="name" @open="(o) => emit('open-object', o)" />
        </NTabPane>
        <NTabPane name="yaml" tab="YAML" display-directive="if">
          <YamlTab
            :cluster="cluster"
            :resource-id="resourceId"
            :name="name"
            :ns="ns"
            :initial-edit="initialEdit"
            :authed="authed"
            :access="access"
            @auth-expired="emit('auth-expired')"
            @editing="(v) => (yamlEditing = v)"
          />
        </NTabPane>
        <NTabPane name="events" tab="Events" display-directive="if">
          <EventsPane :events="events" :error="eventsError" @retry="loadEvents" />
        </NTabPane>
        <NTabPane v-if="kind === 'Pod' && showFiles" name="files" tab="Files" display-directive="if">
          <PodFilesPane
            :cluster="cluster"
            :namespace="ns"
            :pod="name"
            :obj="view"
            :authed="authed"
            @auth-required="emit('require-auth')"
          />
        </NTabPane>
        <NTabPane v-if="kind === 'Pod'" name="metrics" tab="Metrics" display-directive="if">
          <div class="charts vertical">
            <MetricChart :cluster="cluster" target="pod-cpu" :namespace="ns" :name="name" label="CPU (cores)" />
            <MetricChart :cluster="cluster" target="pod-mem" :namespace="ns" :name="name" label="Memory" />
          </div>
        </NTabPane>
        <NTabPane v-if="isNode" name="metrics" tab="Metrics" display-directive="if">
          <div class="charts vertical">
            <MetricChart :cluster="cluster" target="node-cpu" :name="name" label="CPU (cores)" />
            <MetricChart :cluster="cluster" target="node-mem" :name="name" label="Memory" />
            <MetricChart :cluster="cluster" target="node-disk" :name="name" label="Disk" />
          </div>
        </NTabPane>
      </NTabs>
    </NDrawerContent>
  </NDrawer>
</template>
