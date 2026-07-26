{{/* Common naming + label helpers. */}}

{{- define "kweblens.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "kweblens.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s" (include "kweblens.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "kweblens.labels" -}}
app.kubernetes.io/name: {{ include "kweblens.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: kweblens
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
{{- end -}}

{{- define "kweblens.selectorLabels" -}}
app.kubernetes.io/name: {{ include "kweblens.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "kweblens.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "kweblens.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "kweblens.image" -}}
{{- $tag := default .Chart.AppVersion .Values.image.tag -}}
{{- printf "%s:%s" .Values.image.repository $tag -}}
{{- end -}}

{{/* Ingress host — fails fast if enabled without a host (no lab default). */}}
{{- define "kweblens.ingressHost" -}}
{{- required "ingress.enabled=true requires ingress.host (set it in your deploy overlay)" .Values.ingress.host -}}
{{- end -}}
