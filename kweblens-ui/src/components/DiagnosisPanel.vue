<script setup lang="ts">
// Diagnosis: why the things needing attention need it, and what to do about them.
//
// This deepens the overview rather than sitting beside it (#218). The overview says which
// objects need attention and gives a terse reason ("ImagePullBackOff", "0/3 ready"); a
// finding carries the scheduler's actual message, what an exit code meant, and a suggested
// fix. Same question, more depth — so it belongs on the same page, below the tallies.
//
// The summary is rendered from parsed spans, never as HTML. See diagnosis.ts for why.
//
// MOUNTING THIS COSTS NOTHING (#251). The GET is deterministic: findings plus whatever
// summary is already cached for exactly those findings. The model is called only by the
// Analyse button, which is a POST and therefore needs the admin login — so the panel takes
// `authed` and asks for a sign-in rather than firing a request that would 401. Do not move
// the analysis back onto load, however convenient an "auto-refresh on stale" would feel.
import { computed, ref } from 'vue';

import { api } from '../api';
import { failureNotice } from '../apiFailure';
import {
  analyseLabel,
  analysisNote,
  analysisState,
  countLine,
  type DiagnoseResult,
  groupFindings,
  parseSummary,
  sortFindings,
} from '../diagnosis';
import { useAsyncData } from '../composables/useAsyncData';
import ErrorNotice from './ErrorNotice.vue';

const props = defineProps<{ cluster: string; namespace: string | null; authed?: boolean }>();
const emit = defineEmits<{ (e: 'require-auth'): void }>();

const { data, loading, error, reload } = useAsyncData<DiagnoseResult>(
  () => [props.cluster, props.namespace],
  () => api.diagnose(props.cluster, props.namespace ?? undefined),
);

const findings = computed(() => sortFindings(data.value?.findings ?? []));
const groups = computed(() => groupFindings(data.value?.findings ?? []));
const blocks = computed(() => parseSummary(data.value?.summary));
const enriched = computed(() => data.value?.aiEnriched === true);
const state = computed(() => analysisState(data.value));
const note = computed(() => analysisNote(data.value));
const label = computed(() => analyseLabel(state.value));

const analysing = ref(false);
const analyseError = ref<string | null>(null);

/**
 * Spend the call. Guarded on `authed` first so an unauthenticated click opens the sign-in
 * dialog instead of burning a round-trip on a guaranteed 401.
 */
const analyse = async () => {
  if (!props.authed) {
    emit('require-auth');
    return;
  }
  analysing.value = true;
  analyseError.value = null;
  try {
    data.value = await api.analyseDiagnosis(props.cluster, props.namespace ?? undefined);
  } catch (e: unknown) {
    analyseError.value = failureNotice(e);
  } finally {
    analysing.value = false;
  }
};
</script>

<template>
  <section class="dx">
    <header class="dx-head">
      <h3 class="dx-title">Diagnosis</h3>
      <span v-if="data && !loading" class="dx-count">{{ countLine(findings) }}</span>
      <!-- Offered only where it can work: a model is configured and there is something to
           summarise. The label says whether a call has already been spent on this scope. -->
      <button
        v-if="data && !loading && state !== 'unavailable'"
        class="btn dx-analyse"
        :disabled="analysing"
        :title="authed ? 'Send these findings to the configured language model' : 'Sign in to run the analysis'"
        @click="analyse"
      >
        {{ analysing ? 'Analysing…' : label }}
      </button>
    </header>

    <ErrorNotice v-if="error" :message="error" :retrying="loading" @retry="reload" />
    <p v-else-if="loading" class="dx-note">Checking…</p>

    <template v-else-if="data">
      <p v-if="analyseError" class="dx-note dx-analyse-error">Analysis failed: {{ analyseError }}</p>

      <!-- The summary is optional and its absence is normal: it needs an LLM key AND an
           explicit run, and the findings below stand on their own without one. -->
      <div v-if="enriched && blocks.length" class="dx-summary">
        <template v-for="(b, i) in blocks" :key="i">
          <h4 v-if="b.kind === 'heading'" class="dx-h">
            <span v-for="(s, j) in b.spans" :key="j" :class="s.bold ? 'dx-b' : ''">{{ s.text }}</span>
          </h4>
          <p v-else :class="['dx-p', 'dx-d' + b.depth, b.kind === 'bullet' || b.kind === 'numbered' ? 'dx-li' : '']">
            <span v-for="(s, j) in b.spans" :key="j" :class="s.bold ? 'dx-b' : ''">{{ s.text }}</span>
          </p>
        </template>
        <!-- Who wrote it AND when. A reading from before a rollout that presents as current
             is worse than no reading at all. -->
        <p class="dx-attrib">{{ note }}</p>
      </div>

      <!-- Analysed once, then the cluster moved: say so instead of quietly reverting to the
           never-analysed state, which reads as the panel having lost the answer. -->
      <p v-else-if="state === 'outdated'" class="dx-stale">{{ note }}</p>

      <!-- Grouped by title so one repeated check cannot crowd out the rest. Nothing is
           hidden: every finding is still listed under its heading. -->
      <ul v-if="groups.length" class="dx-list">
        <li v-for="(g, gi) in groups" :key="gi" :class="'dx-item dx-' + g.severity">
          <div class="dx-item-head">
            <span :class="'dx-sev dx-sev-' + g.severity">{{ g.severity }}</span>
            <span class="dx-item-title">{{ g.title }}</span>
            <span v-if="g.findings.length > 1" class="dx-times">×{{ g.findings.length }}</span>
          </div>
          <div v-for="(f, i) in g.findings" :key="i" class="dx-one">
            <div class="dx-obj">{{ f.object }}</div>
            <p v-if="f.detail" class="dx-detail">{{ f.detail }}</p>
          </div>
          <p v-if="g.findings[0].suggestedFix" class="dx-fix"><strong>Try:</strong> {{ g.findings[0].suggestedFix }}</p>
        </li>
      </ul>
      <p v-else class="dx-note">Nothing looks wrong in this scope.</p>
    </template>
  </section>
</template>
