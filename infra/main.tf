terraform {
  required_version = ">= 1.6.0"

  required_providers {
    kind = {
      source  = "tehcyx/kind"
      version = "0.13.0"
    }
  }
}

provider "kind" {}

resource "kind_cluster" "cloud_native" {
  name            = "camel-cloud-native"
  wait_for_ready  = true
  kubeconfig_path = pathexpand("~/.kube/config")

  kind_config {
    kind        = "Cluster"
    api_version = "kind.x-k8s.io/v1alpha4"

    node {
      role = "control-plane"

      extra_port_mappings {
        container_port = 30080
        host_port      = 8080
        protocol       = "TCP"
      }

      extra_port_mappings {
        container_port = 30443
        host_port      = 8443
        protocol       = "TCP"
      }
    }

    node {
      role = "worker"
    }

    node {
      role = "worker"
    }
  }
}

output "cluster_context" {
  value = kind_cluster.cloud_native.context_name
}