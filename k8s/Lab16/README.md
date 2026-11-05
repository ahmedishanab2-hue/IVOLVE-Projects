# Lab 16: Pod Resource Management with CPU and Memory Requests and Limits

## Objective

Update the existing `ivolveapp` Deployment so each app container declares CPU/memory **requests** and **limits**, then verify the settings and watch real-time usage.

## What the lab asked for

| Field | Value |
|-------|-------|
| Requests CPU | `1` (1 vCPU) |
| Requests memory | `1Gi` |
| Limits CPU | `2` (2 vCPUs) |
| Limits memory | `2Gi` |

Also required:

- `kubectl describe pod` shows the applied requests/limits
- `kubectl top pod` shows live usage (metrics-server must run in the cluster)

## My setup

- Minikube on WSL (2 nodes: `minikube` and `minikube-m02`)
- Namespace `ivolve` with the `ivolveapp` Deployment from Lab 15
- ResourceQuota `ivolvequota` (pods=3) — unchanged by this lab
- Image `ahmedshanab215/ivolveapp:latest` from Lab 8

## Step 1: Enable metrics-server

Minikube ships a metrics-server addon (disabled by default):

```bash
minikube addons enable metrics-server
```

Wait for it to become ready:

```bash
kubectl get pods -n kube-system -l k8s-app=metrics-server
kubectl top pod -n kube-system | head
```

`kubectl top` fails with `Metrics API not available` until this pod is `Running` and has scraped nodes once (usually ~30s).

## Step 2: Update the Deployment

Full manifest: [`deployment.yaml`](./deployment.yaml). The resource block on the app container:

```yaml
containers:
  - name: ivolveapp
    image: ahmedshanab215/ivolveapp:latest
    resources:
      requests:
        cpu: "1"
        memory: 1Gi
      limits:
        cpu: "2"
        memory: 2Gi
```

The short-lived `init-db` init container got smaller settings so it does not reserve a full vCPU for its few seconds of work:

```yaml
initContainers:
  - name: init-db
    resources:
      requests:
        cpu: 100m
        memory: 128Mi
      limits:
        cpu: 500m
        memory: 512Mi
```

Apply:

```bash
kubectl apply -f deployment.yaml
kubectl rollout status deployment/ivolveapp -n ivolve
```

The strategy is still `maxSurge: 0` / `maxUnavailable: 1`, so pods are replaced one at a time within the 3-pod quota.

## Step 3: Verify with `kubectl describe pod`

```bash
POD=$(kubectl get pod -n ivolve -l app=ivolveapp -o jsonpath='{.items[0].metadata.name}')
kubectl describe pod -n ivolve "$POD"
```

Under the `ivolveapp` container section:

```text
    Limits:
      cpu:     2
      memory:  2Gi
    Requests:
      cpu:     1
      memory:  1Gi
```

Compact check across both replicas:

```bash
kubectl get pod -n ivolve -l app=ivolveapp \
  -o custom-columns='POD:.metadata.name,CPU_REQ:.spec.containers[0].resources.requests.cpu,CPU_LIM:.spec.containers[0].resources.limits.cpu,MEM_REQ:.spec.containers[0].resources.requests.memory,MEM_LIM:.spec.containers[0].resources.limits.memory'
```

```text
POD                         CPU_REQ   CPU_LIM   MEM_REQ   MEM_LIM
ivolveapp-...-...           1         2         1Gi       2Gi
ivolveapp-...-...           1         2         1Gi       2Gi
```

## Step 4: Monitor with `kubectl top pod`

```bash
kubectl top pod -n ivolve
```

```text
NAME                         CPU(cores)   MEMORY(bytes)
ivolveapp-...-xxxxx          3m           46Mi
ivolveapp-...-yyyyy          3m           45Mi
ivolvedb-0                   118m         340Mi
```

Per-container breakdown:

```bash
kubectl top pod -n ivolve --containers
```

Node-level view:

```bash
kubectl top node
```

Usage sits well under the `2` CPU / `2Gi` limits — expected for this small Node.js app (Burstable QoS: requests &lt; limits).

## Files

```
Lab16/
├── deployment.yaml   # ivolveapp Deployment with requests + limits
└── README.md
```

## Notes

- **Requests** are reserved by the scheduler; **limits** are the hard cap (CPU throttled, memory OOMKilled).
- With `requests ≠ limits` the pods are **Burstable**, not Guaranteed.
- The namespace ResourceQuota only limits pod count (`pods: "3"`), not CPU/memory, so these values do not interact with the quota.
- `minikube-m02` has plenty of allocatable CPU (12), so two pods × 1 CPU request schedule fine alongside MySQL.
