#!/usr/bin/env bash
# Build the kweblens image (Spring Boot buildpacks), push it to a registry, and
# helm-upgrade the chart. Fully ARG-DRIVEN — no lab-specific defaults live here; a
# private deploy overlay supplies --registry / --values / --pull-secret. See
# docs/deployment.md.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART="${REPO_ROOT}/deploy/helm/kweblens"

REGISTRY=""
NAMESPACE="kweblens"
RELEASE="kweblens"
VALUES=""
PULL_SECRET=""
TAG=""
PUSH="true"
BUILD="true"
EXTRA_HELM_ARGS=()

usage() {
  cat <<EOF
Usage: deploy-k8s.sh --registry <host[/path]> [options]

  --registry <host>     Image registry to push to (e.g. registry.example.com). Required.
  --namespace <ns>      Target namespace (default: kweblens).
  --release <name>      Helm release name (default: kweblens).
  --values <file>       Extra Helm values file (repeatable). Overlay supplies lab values.
  --pull-secret <name>  imagePullSecrets entry to set on the release.
  --tag <tag>           Image tag (default: chart appVersion / project version).
  --no-build            Skip the buildpacks image build (reuse an existing tag).
  --no-push             Build but do not push the image.
  --set k=v             Passed through to helm (repeatable).
  -h, --help            This help.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --registry) REGISTRY="$2"; shift 2 ;;
    --namespace) NAMESPACE="$2"; shift 2 ;;
    --release) RELEASE="$2"; shift 2 ;;
    --values) VALUES="${VALUES} -f $2"; shift 2 ;;
    --pull-secret) PULL_SECRET="$2"; shift 2 ;;
    --tag) TAG="$2"; shift 2 ;;
    --no-build) BUILD="false"; shift ;;
    --no-push) PUSH="false"; shift ;;
    --set) EXTRA_HELM_ARGS+=(--set "$2"); shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; usage; exit 2 ;;
  esac
done

[[ -n "$REGISTRY" ]] || { echo "--registry is required" >&2; exit 2; }

# Resolve the tag from the project version when not given.
if [[ -z "$TAG" ]]; then
  TAG="$("${REPO_ROOT}/mvnw" -q -f "${REPO_ROOT}/pom.xml" help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)"
fi
IMAGE="${REGISTRY}/kweblens:${TAG}"
echo "==> image: ${IMAGE}"

if [[ "$BUILD" == "true" ]]; then
  echo "==> building image with Spring Boot buildpacks"
  "${REPO_ROOT}/mvnw" -Pdocker -pl kweblens-web -am package \
    -Ddocker.image.name="${IMAGE}" -Ddocker.publish="${PUSH}" -DskipTests
elif [[ "$PUSH" == "true" ]]; then
  echo "==> pushing existing image ${IMAGE}"
  docker push "${IMAGE}"
fi

echo "==> helm upgrade --install ${RELEASE} (ns ${NAMESPACE})"
# shellcheck disable=SC2086
helm upgrade --install "${RELEASE}" "${CHART}" \
  --namespace "${NAMESPACE}" --create-namespace \
  --set image.repository="${REGISTRY}/kweblens" \
  --set-string image.tag="${TAG}" \
  ${PULL_SECRET:+--set imagePullSecrets[0].name="${PULL_SECRET}"} \
  ${VALUES} \
  "${EXTRA_HELM_ARGS[@]}"

echo "==> rollout"
kubectl -n "${NAMESPACE}" rollout status "deploy/${RELEASE}" --timeout=120s
