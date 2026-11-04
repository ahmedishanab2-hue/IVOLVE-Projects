# Lab 13: Persistent Storage Setup for Application Logging

## Objective

Create a Persistent Volume (PV) and a Persistent Volume Claim (PVC) that provide shared storage for application logs.

| Resource | Name | Details |
|----------|------|---------|
| PV | `ivolvepv` | 1Gi, `hostPath: /mnt/app-logs`, `ReadWriteMany`, reclaim policy `Retain` |
| PVC | `ivolvepvc` | Requests 1Gi, `ReadWriteMany`, in the `ivolve` namespace |

No StorageClass is used (`storageClassName: ""`).

> **Note on names:** Kubernetes requires lowercase resource names, so `ivolvePV` and `ivolvePVC` from the lab text are created as `ivolvepv` and `ivolvepvc`.

## Environment

- Minikube on WSL (2 nodes: `minikube` and `minikube-m02`)
- `kubectl` configured for the Minikube cluster
- The `ivolve` namespace from Lab 11 already exists

## Steps

### 1. Make sure the cluster is running

```bash
minikube status
kubectl get nodes
kubectl get ns ivolve
```

If the cluster is down, start it with `minikube start`. If the namespace is missing, create it with `kubectl create ns ivolve`.

### 2. Create the manifest

Save the following as `storage.yaml`:

```yaml
apiVersion: v1
kind: PersistentVolume
metadata:
  name: ivolvepv
spec:
  capacity:
    storage: 1Gi
  accessModes:
    - ReadWriteMany
  persistentVolumeReclaimPolicy: Retain
  storageClassName: ""
  hostPath:
    path: /mnt/app-logs
    type: DirectoryOrCreate
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: ivolvepvc
  namespace: ivolve
spec:
  accessModes:
    - ReadWriteMany
  resources:
    requests:
      storage: 1Gi
  storageClassName: ""
  volumeName: ivolvepv
```

### 3. Apply the manifest

```bash
kubectl apply -f storage.yaml
```

Expected output:

```
persistentvolume/ivolvepv created
persistentvolumeclaim/ivolvepvc created
```

### 4. Verify the PV and PVC

```bash
kubectl get pv
kubectl get pvc -n ivolve
```

Expected result:

- PV `ivolvepv`: 1Gi, `RWX`, `Retain`, status `Bound`, claim `ivolve/ivolvepvc`
- PVC `ivolvepvc`: status `Bound`, volume `ivolvepv`, 1Gi, `RWX`

### 5. (Optional) Test with a pod

```bash
kubectl apply -f - <<EOF
apiVersion: v1
kind: Pod
metadata:
  name: log-test
  namespace: ivolve
spec:
  containers:
  - name: app
    image: busybox
    command: ["sh","-c","echo hello > /logs/test.log; sleep 3600"]
    volumeMounts:
    - name: logs
      mountPath: /logs
  volumes:
  - name: logs
    persistentVolumeClaim:
      claimName: ivolvepvc
EOF

kubectl get pod log-test -n ivolve -o wide
```

Check which node the pod is running on (the `NODE` column), then read the file from that node:

```bash
minikube ssh -n minikube -- cat /mnt/app-logs/test.log
# or, if the pod is on the worker:
minikube ssh -n minikube-m02 -- cat /mnt/app-logs/test.log
```

Expected output: `hello`

### 6. Cleanup

```bash
kubectl delete pod log-test -n ivolve
```

The PV and PVC remain `Bound`, which is the desired end state for this lab.

## Troubleshooting

| Problem | Cause | Fix |
|---------|-------|-----|
| `connection refused` on `127.0.0.1:<port>` | Minikube cluster is not running | Run `minikube start` |
| `exceeded quota: ivolvequota` when creating the test pod | The Lab 11 ResourceQuota allows only 3 pods in `ivolve`, and 3 are already running | Delete an unneeded bare pod (`kubectl delete pod <name> -n ivolve`) or test with a hostPath pod in another namespace |
| Pod can't schedule on the worker | The Lab 10 taint `workload=ivolveApp:NoSchedule` may be on `minikube-m02` | Add a matching toleration to the pod |
| Resource names rejected | Uppercase letters in names | Use lowercase names |

## Notes

- `storageClassName: ""` explicitly means "no StorageClass", so no default class is assigned and the PVC binds only to a PV without a class.
- A PV is cluster-wide, while a PVC is namespaced. The PVC lives in `ivolve`, so only pods in that namespace can use it.
- `hostPath` is local to each node. Pods on different nodes each see their own `/mnt/app-logs`. This is acceptable for the lab but is not true shared storage.
- With Minikube, `/mnt/app-logs` is on the Minikube node, not on the WSL filesystem. Running `minikube delete` removes that data.
