output "cluster_name" {
  description = "Nom du cluster Kind provisionné"
  value       = kind_cluster.default.name
}

output "kubeconfig_path" {
  description = "Chemin du kubeconfig généré par Kind"
  value       = kind_cluster.default.kubeconfig_path
}

output "local_urls" {
  description = "Interfaces exposées via Envoy Gateway"
  value = {
    argocd   = "http://argocd.localhost:${var.host_http_port}"
    grafana  = "http://grafana.localhost:${var.host_http_port}"
    kafka    = "http://kafka.localhost:${var.host_http_port}"
    activemq = "http://activemq.localhost:${var.host_http_port}/admin"
    qdrant   = "http://qdrant.localhost:${var.host_http_port}/dashboard"
    apicurio = "http://apicurio.localhost:${var.host_http_port}/apis/registry/v3/system/info"
  }
}