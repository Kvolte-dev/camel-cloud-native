# Camel Cloud-Native

Plateforme d'integration evenementielle **Apache Camel 4.22 / Spring Boot 4 / Java 25** deployee en mode **GitOps (ArgoCD App-of-Apps)** sur un cluster **Kind multi-noeuds** amorce par **Terraform**, avec validation de contrats **Apicurio Registry**, **observabilite** et **auto-remediation IA en boucle fermee (Qdrant + Ollama)**.

---

## Architecture

Terraform (Day 0 - infra/terraform/)
   |-- Cluster Kind (1 control-plane + 2 workers, port 8080 -> 30080)
   |-- Operateurs Helm (Envoy Gateway, Strimzi, KEDA, Sealed Secrets, ArgoCD)
   +-- Root App ArgoCD --> surveille gitops/argocd/

ArgoCD App-of-Apps (Day 1/2 - GitOps)
   |-- messaging      : Strimzi Kafka (KRaft), Kafbat UI, ActiveMQ Artemis, Apicurio Registry
   |-- observability  : Prometheus, Grafana, Loki, Alloy
   |-- gateway        : Envoy Gateway (Gateway API v1)
   |-- ai             : Qdrant (vector store runbooks) + Ollama (RAG / LLM)
   +-- camel-app      : image ghcr.io/kvolte-dev/camel-app:main (non-root)

Flux metier & boucle fermee IA
   ActiveMQ (orders.in) --> Camel (orders.process + JSON Schema Apicurio)
                                 |-- Valide --> traitement nominal
                                 +-- Erreur / Schema KO --> orders.dlq
                                                                |
                        +---------------------------------------+
                        v
               DlqAiAnalyzerRoute (RAG Qdrant + Ollama)
                        |-- Archive --> orders.dlq.analyzed
                        +-- AiRemediationGuard (4 garde-fous)
                               |-- APPROVED --> reinjection orders.process (aiRetryCount=1)
                               +-- REJECTED --> blocage + metrique app_ai_remediation_total

---

## Stack technique

| Couche | Composant | Role |
| :--- | :--- | :--- |
| IaC & GitOps | Terraform, Kind, ArgoCD, Sealed Secrets | Provisionnement Day 0 et synchronisation continue Day 1/2 |
| Reseau & Autoscaling | Envoy Gateway, KEDA | Routage HTTP local (*.localhost:8080) et scalabilite evenementielle |
| Messagerie & Contrats | Strimzi Kafka, ActiveMQ, Kafbat UI, Apicurio | Transport d'evenements, DLQ et validation JSON Schema |
| Application & IA | Java 25, Spring Boot 4, Camel 4.22, Qdrant, Ollama | Routes Camel, recherche vectorielle de runbooks et remediation gardee |
| Observabilite & CI/CD | Prometheus, Grafana, Loki, Alloy, GitHub Actions, Trivy, GHCR | Metriques/logs, tests JUnit 6 / AdviceWith, scan securite et registre d'images |

---

## Interfaces locales (via Envoy Gateway)

| Service | URL |
| :--- | :--- |
| ArgoCD | http://argocd.localhost:8080 |
| Grafana | http://grafana.localhost:8080 |
| Kafbat UI | http://kafka.localhost:8080 |
| ActiveMQ Console | http://activemq.localhost:8080/admin |
| Qdrant Dashboard | http://qdrant.localhost:8080/dashboard |
| Apicurio Registry API | http://apicurio.localhost:8080/apis/registry/v3/system/info |

---

## Demarrage rapide

### 1. Provisionner le cluster et ArgoCD (Terraform)

cd infra\terraform
terraform init
terraform apply -auto-approve
kind export kubeconfig --name camel-cloud-native

### 2. Lancer les tests en local

cd app
mvn test -B

### 3. Detruire l'environnement

cd infra\terraform
terraform destroy -auto-approve

---

## Scenarios de validation

1. Auto-remediation approuvee (APPROVED) : envoyer {"orderId": 1001, "item": "FAIL", "qty": 2} dans orders.in -> passage en orders.dlq, analyse RAG (RB-ORD-001), archivage dans orders.dlq.analyzed, validation Apicurio OK et reinjection dans orders.process (aiRetryCount=1).
2. Blocage par garde-fou (POLICY_BLOCKED / schema) : envoyer {"orderId": "BAD", "qty": -1} dans orders.in -> routage direct en orders.dlq, analyse IA, rejet par AiRemediationGuard sans reinjection et increment de app_ai_remediation_total.