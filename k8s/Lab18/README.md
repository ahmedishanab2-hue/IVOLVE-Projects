# Lab 18: Node-Wide Pod Management with DaemonSet (Monitoring Stack)

In this lab I built a monitoring stack on Kubernetes: Prometheus to collect metrics, Alertmanager for alerts, Grafana for dashboards, and a node-exporter DaemonSet that runs one pod on every node to expose the node's metrics.

## What the lab asked for

- Create a `monitoring` namespace
- Deploy Prometheus, Alertmanager and Grafana in it
- Deploy a DaemonSet for Prometheus node-exporter
- Validate that a node-exporter pod is scheduled on each node
- Confirm the metrics are exposed on port 9100 at `/metrics`
- Configure Prometheus to collect data from node-exporter
- Configure a Grafana dashboard for Kubernetes

## My setup

- Minikube on WSL with two nodes: `minikube` and `minikube-m02`
- `kubectl` and `helm` installed

## How I approached it

Instead of writing every manifest by hand, I installed the `kube-prometheus-stack` Helm chart. One chart deploys all of the components the lab asks for:

| Lab requirement | What the chart creates |
|-----------------|------------------------|
| Prometheus | StatefulSet `prometheus-prometheus-kube-prometheus-prometheus` (managed by the Prometheus Operator) |
| Alertmanager | StatefulSet `alertmanager-prometheus-kube-prometheus-alertmanager` |
| Grafana | Deployment `prometheus-grafana`, with Kubernetes dashboards included |
| node-exporter DaemonSet | DaemonSet `prometheus-prometheus-node-exporter` |
| Prometheus scraping node-exporter | A ServiceMonitor that the operator turns into a scrape target |

## Step 1: Create the namespace

I generated the manifest with `-o yaml` so I'd have a file to keep, then applied it:

```bash
mkdir -p ~/ivolve/IVOLVE-Projects/k8s/Lab18 && cd ~/ivolve/IVOLVE-Projects/k8s/Lab18

kubectl create namespace monitoring --dry-run=client -o yaml > monitoringNamespace.yaml
kubectl apply -f monitoringNamespace.yaml
```

Result:

```
namespace/monitoring created
```

## Step 2: Add the Prometheus Helm repository

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm repo update
```

Result:

```
"prometheus-community" has been added to your repositories
...Successfully got an update from the "prometheus-community" chart repository
Update Complete. ⎈Happy Helming!⎈
```

## Step 3: Install the monitoring stack

```bash
helm install prometheus prometheus-community/kube-prometheus-stack --namespace monitoring
```

Result:

```
NAME: prometheus
NAMESPACE: monitoring
STATUS: deployed
REVISION: 1
```

The images are fairly large, so the pods take several minutes to start. I watched them with:

```bash
kubectl get pods -n monitoring
```

When everything is ready, the list looks like this:

```
NAME                                                     READY   STATUS
alertmanager-prometheus-kube-prometheus-alertmanager-0   2/2     Running
prometheus-grafana-5dcc579ff7-g8vsz                      3/3     Running
prometheus-kube-prometheus-operator-667954c964-q7mbn     1/1     Running
prometheus-kube-state-metrics-cb5d78584-wv8cd            1/1     Running
prometheus-prometheus-kube-prometheus-prometheus-0       2/2     Running
prometheus-prometheus-node-exporter-9xr4n                1/1     Running
prometheus-prometheus-node-exporter-rjvg2                1/1     Running
```

That covers Prometheus, Alertmanager, Grafana and the node-exporter DaemonSet in the `monitoring` namespace.

## Step 4: Validate that node-exporter runs on every node

```bash
kubectl get ds -n monitoring
kubectl get pods -n monitoring -o wide | grep node-exporter
```

What to look for:

- The DaemonSet `prometheus-prometheus-node-exporter` shows `DESIRED 2` and `READY 2`, matching my two nodes
- The `NODE` column lists one node-exporter pod on `minikube` and one on `minikube-m02`

I also saved the DaemonSet as a manifest for the repo:

```bash
kubectl get ds prometheus-prometheus-node-exporter -n monitoring -o yaml > node-exporter-ds.yaml
```

## Step 5: Confirm metrics are exposed on port 9100

I forwarded the node-exporter service to my machine and requested the metrics endpoint:

```bash
kubectl port-forward -n monitoring svc/prometheus-prometheus-node-exporter 9100:9100 &
sleep 2 && curl -s localhost:9100/metrics | head -5
kill %1
```

The output starts with `# HELP` and `# TYPE` lines. That is the Prometheus text format, with metrics such as `node_cpu_seconds_total` and `node_memory_MemAvailable_bytes` further down. I can also check the other node directly:

```bash
minikube ssh -n minikube-m02 -- curl -s localhost:9100/metrics | head -3
```

## Step 6: Check that Prometheus collects from node-exporter

The chart configures this scrape automatically through a ServiceMonitor, which the Prometheus Operator reads to build the scrape configuration:

```bash
kubectl get servicemonitor -n monitoring | grep node-exporter
```

To prove the data is flowing, I opened the Prometheus UI:

```bash
kubectl port-forward -n monitoring svc/prometheus-kube-prometheus-prometheus 9090:9090
```

Then in the browser at `http://localhost:9090`:

- **Status > Targets**: the `node-exporter` target shows **UP**, with one endpoint per node
- **Graph**: the query `up{job="node-exporter"}` returns two series with the value `1`

I pressed `Ctrl+C` in the terminal afterwards to stop the forward.

## Step 6b: Get the Grafana login

The Helm chart stores the Grafana credentials in a Secret:

```bash
kubectl get secret prometheus-grafana -n monitoring -o jsonpath="{.data.admin-user}" | base64 -d ; echo
kubectl get secret prometheus-grafana -n monitoring -o jsonpath="{.data.admin-password}" | base64 -d ; echo
```

The username is `admin`. The password is generated at install time, so it is different for every installation. Secrets are stored base64-encoded, which is why the commands pipe through `base64 -d`.

## Step 7: Open Grafana and show the Kubernetes dashboard

I forwarded the Grafana service to local port 3001:

```bash
kubectl port-forward -n monitoring svc/prometheus-grafana 3001:80
```

The terminal stays busy while the forward runs, so I used a second terminal for other commands. In the browser I opened `http://localhost:3001` and logged in with the credentials from the previous step.

The chart already connects Grafana to Prometheus as its data source and ships with Kubernetes dashboards. Under **Dashboards**, I opened:

- **Kubernetes / Compute Resources / Cluster**: CPU, memory and network use across the cluster
- **Node Exporter / Nodes**: per-node metrics that come from the node-exporter DaemonSet

I set the time range to **Last 15 minutes** so recent data shows up.

To import a community dashboard instead, go to **Dashboards > New > Import**, enter ID `315` (Kubernetes cluster monitoring) or `1860` (Node Exporter Full), choose **Prometheus** as the data source and click **Import**.

## Final result

| Requirement | Result |
|-------------|--------|
| `monitoring` namespace | Created from `monitoringNamespace.yaml` |
| Prometheus | Running in `monitoring` (`2/2`) |
| Alertmanager | Running in `monitoring` (`2/2`) |
| Grafana | Running in `monitoring` (`3/3`) |
| node-exporter DaemonSet | `prometheus-prometheus-node-exporter` in `monitoring` |
| One pod on each node | One pod on `minikube`, one on `minikube-m02` |
| Metrics on `:9100/metrics` | Prometheus-format metrics returned |
| Prometheus collects from node-exporter | `node-exporter` target is **UP**, `up{job="node-exporter"}` = 1 |
| Grafana Kubernetes dashboard | Cluster and Node Exporter dashboards show live data |

## Notes

- A DaemonSet runs one pod per node, so adding a node to the cluster automatically adds a node-exporter pod to it.
- The Prometheus Operator watches ServiceMonitor objects and updates Prometheus's scrape configuration for me, so there's no need to edit `prometheus.yml` by hand.
- Port-forwards are only for local access. They stop when the terminal is closed or `Ctrl+C` is pressed.
- The whole stack is fairly heavy for a small Minikube setup, so give the pods a few minutes to become ready after installing.
- To remove everything, run `helm uninstall prometheus -n monitoring`, and `kubectl delete namespace monitoring` for the namespace.
