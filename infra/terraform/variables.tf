variable "cluster_name" {
  description = "Nom du cluster Kind local"
  type        = string
  default     = "camel-cloud-native"
}

variable "host_http_port" {
  description = "Port HTTP exposé sur l'hôte Windows vers Envoy Gateway (NodePort 30080)"
  type        = number
  default     = 8080
}

variable "git_repo_url" {
  description = "URL du dépôt GitOps surveillé par ArgoCD"
  type        = string
  default     = "https://github.com/kvolte-dev/camel-cloud-native.git"
}

variable "git_revision" {
  description = "Branche ou tag Git suivi par ArgoCD"
  type        = string
  default     = "main"
}