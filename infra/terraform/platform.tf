# 1. Envoy Gateway (Gateway API v1)
resource "helm_release" "envoy_gateway" {
  name             = "eg"
  repository       = "oci://docker.io/envoyproxy"
  chart            = "gateway-helm"
  namespace        = "envoy-gateway-system"
  create_namespace = true
  wait             = true
  timeout          = 600

  depends_on = [kind_cluster.default]
}

# 2. Strimzi Kafka Operator (mode KRaft)
resource "helm_release" "strimzi" {
  name             = "strimzi-kafka-operator"
  repository       = "oci://quay.io/strimzi-helm"
  chart            = "strimzi-kafka-operator"
  namespace        = "messaging"
  create_namespace = true
  wait             = true
  timeout          = 600

  set {
    name  = "watchAnyNamespace"
    value = "true"
  }

  depends_on = [kind_cluster.default]
}

# 3. KEDA (autoscaling sur le lag Kafka)
resource "helm_release" "keda" {
  name             = "keda"
  repository       = "https://kedacore.github.io/charts"
  chart            = "keda"
  namespace        = "keda"
  create_namespace = true
  wait             = true
  timeout          = 600

  depends_on = [kind_cluster.default]
}

# 4. Sealed Secrets (déchiffrement GitOps des secrets)
resource "helm_release" "sealed_secrets" {
  name       = "sealed-secrets"
  repository = "https://bitnami-labs.github.io/sealed-secrets"
  chart      = "sealed-secrets"
  namespace  = "kube-system"
  wait       = true

  set {
    name  = "fullnameOverride"
    value = "sealed-secrets-controller"
  }

  depends_on = [kind_cluster.default]
}

# 5. ArgoCD (moteur GitOps, HTTP interne pour Envoy Gateway)
resource "helm_release" "argocd" {
  name             = "argocd"
  repository       = "https://argoproj.github.io/argo-helm"
  chart            = "argo-cd"
  namespace        = "argocd"
  create_namespace = true
  wait             = true
  timeout          = 600

  values = [
    yamlencode({
      configs = {
        params = {
          "server.insecure" = true
        }
      }
    })
  ]

  depends_on = [kind_cluster.default]
}

# 6. App-of-Apps : amorce automatiquement toutes les Applications de gitops/argocd/
resource "helm_release" "argocd_root_app" {
  name       = "argocd-root-app"
  repository = "https://argoproj.github.io/argo-helm"
  chart      = "argocd-apps"
  namespace  = "argocd"

  values = [
    yamlencode({
      applications = {
        root-apps = {
          namespace = "argocd"
          project   = "default"
          source = {
            repoURL        = var.git_repo_url
            targetRevision = var.git_revision
            path           = "gitops/argocd"
          }
          destination = {
            server    = "https://kubernetes.default.svc"
            namespace = "argocd"
          }
          syncPolicy = {
            automated = {
              prune    = true
              selfHeal = true
            }
          }
        }
      }
    })
  ]

  depends_on = [
    helm_release.argocd,
    helm_release.envoy_gateway,
    helm_release.strimzi,
    helm_release.keda,
    helm_release.sealed_secrets
  ]
}