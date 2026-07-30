<script setup lang="ts">
/**
 * Read-only diagnostics — surface 3 of the #27 config design.
 *
 * Answers two questions that are easy to confuse and cheap to separate: "why is this screen
 * empty?" (a property of the CLUSTER — its API groups, whether a metrics backend was found)
 * and "what am I running?" (a property of the INSTANCE).
 *
 * Read-only on purpose. Deployment configuration is not editable here: it is a security
 * surface, and runtime edits would drift from the deployed manifest. Every value says where
 * it is set instead.
 */
import { NModal, NSpin } from 'naive-ui';
import { ref, watch } from 'vue';

import { api } from '../api';
import type { AboutInfo, ClusterDiagnostics } from '../types';

const props = defineProps<{ cluster: string | null }>();
const show = defineModel<boolean>({ required: true });

const diag = ref<ClusterDiagnostics | null>(null);
const about = ref<AboutInfo | null>(null);
const error = ref<string | null>(null);
const loading = ref(false);

// Probing costs API calls (service discovery, API-group lookups), so it happens when the
// panel opens rather than on every render.
watch([show, () => props.cluster], ([open, cluster]) => {
  if (!open) {
    return;
  }
  loading.value = true;
  error.value = null;
  const jobs: Promise<unknown>[] = [
    api
      .about()
      .then((a) => (about.value = a))
      .catch((e) => (error.value = String(e))),
  ];
  if (cluster) {
    diag.value = null;
    jobs.push(
      api
        .clusterDiagnostics(cluster)
        .then((d) => (diag.value = d))
        .catch((e) => (error.value = String(e))),
    );
  }
  Promise.all(jobs).finally(() => (loading.value = false));
});
</script>

<template>
  <NModal v-model:show="show" preset="card" title="Diagnostics" class="diag-modal" :bordered="false">
    <NSpin :show="loading">
      <div v-if="error" class="diag-error">{{ error }}</div>

      <section v-if="diag" class="diag-sec">
        <h4>
          Cluster <span class="diag-mono">{{ diag.clusterId }}</span>
          <span class="diag-dim">· Kubernetes {{ diag.kubernetesVersion }}</span>
        </h4>
        <ul class="diag-caps">
          <li v-for="c in diag.capabilities" :key="c.name" :class="{ off: !c.available }">
            <span class="diag-dot" :class="c.available ? 'ok' : 'missing'" />
            <div>
              <div class="diag-name">{{ c.name }}</div>
              <!-- The whole point: not just a tick, but what was found or what to install. -->
              <div class="diag-detail">{{ c.detail }}</div>
            </div>
          </li>
        </ul>
      </section>

      <section v-if="about" class="diag-sec">
        <h4>Application</h4>
        <dl class="diag-kv">
          <dt>Version</dt>
          <dd class="diag-mono">
            {{ about.version }}<span v-if="about.buildTime"> · {{ about.buildTime }}</span>
          </dd>
          <dt>Clusters</dt>
          <dd>
            {{ about.clusterCount }} registered<span v-if="about.configuredClusters">
              ({{ about.configuredClusters }} from config)</span
            >
          </dd>
          <dt>Ambient kubeconfig</dt>
          <dd>{{ about.loadKubeconfig ? 'loaded on startup' : 'not loaded' }}</dd>
          <dt>Security</dt>
          <dd>{{ about.security.mode }}</dd>
          <dt>Admin user</dt>
          <dd class="diag-mono">
            {{ about.security.adminUsername }}
            <span class="diag-dim">
              · password {{ about.security.adminPasswordConfigured ? 'configured' : 'generated at startup' }}</span
            >
          </dd>
          <dt>AI diagnostics</dt>
          <dd>{{ about.aiEnabled ? 'enabled' : 'disabled' }}</dd>
          <dt>Simulator</dt>
          <dd>
            <span v-if="about.simulator.enabled">
              on · cluster {{ about.simulator.clusterId }} · {{ about.simulator.size }} objects across
              {{ about.simulator.namespaces }} namespaces
            </span>
            <span v-else>off</span>
          </dd>
        </dl>
        <!-- Stated rather than implied: #25 ranked identity the project's biggest gap, and a
             diagnostics panel that quietly omitted it would be the wrong kind of reassuring. -->
        <p class="diag-warn">
          <template v-if="!about.security.perUserIdentity">
            No per-user identity: one shared admin account, no OIDC.
          </template>
          <template v-if="!about.security.rbacAware">
            Not RBAC-aware — kweblens acts with its own credentials, so it may offer actions your own permissions would
            not allow.
          </template>
          Put authentication in front before exposing this instance.
        </p>
        <p class="diag-dim diag-foot">
          Configuration is set via environment variables / ConfigMap and is deliberately not editable here.
        </p>
      </section>
    </NSpin>
  </NModal>
</template>
