# Lab 10: Kubernetes Node Management, Taints, Labels, and Pod Scheduling

## Objective

- Add a worker node to an existing Minikube cluster.
- Apply a taint to control Pod scheduling.
- Label the worker node for identification.
- Deploy a Pod using a `nodeSelector` and a matching toleration.
- Verify that the Pod is scheduled on the intended worker node.

## Step 1: Check the Existing Cluster

Check the nodes in the current Minikube cluster:

```bash
kubectl get nodes
```

Initially, the cluster contained one control-plane node:

```text
NAME       STATUS   ROLES           AGE   VERSION
minikube   Ready    control-plane   14d   v1.37.0
```

## Step 2: Add a Worker Node

Add a worker node to the existing Minikube cluster:

```bash
minikube node add
```

Minikube created a new worker node named `minikube-m02`.

Verify the nodes:

```bash
kubectl get nodes
```

The cluster now contains a control-plane node and an additional worker node.

## Step 3: Apply a Taint to the Worker Node

Apply the required taint to prevent Pods without a matching toleration from being scheduled on the worker node:

```bash
kubectl taint nodes minikube-m02 workload=ivolveApp:NoSchedule
```

### Verify the Taint

```bash
kubectl describe node minikube-m02
```

Look for the following entry:

```text
Taints: workload=ivolveApp:NoSchedule
```

**Explanation:**

- `workload`: Taint key.
- `ivolveApp`: Taint value.
- `NoSchedule`: Prevents new Pods that do not tolerate this taint from being scheduled on the node.

## Step 4: Label the Worker Node

Add the label `project=ivolve` to identify the worker node for targeted Pod scheduling:

```bash
kubectl label node minikube-m02 project=ivolve
```

Expected output:

```text
node/minikube-m02 labeled
```

### Verify the Label

```bash
kubectl get nodes -L project
```

Expected output:

```text
NAME           STATUS   ROLES           AGE   VERSION   PROJECT
minikube       Ready    control-plane   14d   v1.37.0
minikube-m02   Ready    <none>          ...   v1.37.0   ivolve
```

The `PROJECT` column confirms that the worker node has the required label.

## Step 5: Create the Pod Manifest

Create a YAML file:

```bash
nano ivolve-app.yaml
```

Add the following configuration:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: ivolve-app
spec:
  nodeSelector:
    project: ivolve
  tolerations:
    - key: workload
      operator: Equal
      value: ivolveApp
      effect: NoSchedule
  containers:
    - name: nginx
      image: nginx:latest
```

Save the file and exit the editor.

### Configuration Explanation

- **`nodeSelector`**: Selects a node labeled `project=ivolve`.
- **`tolerations`**: Allows the Pod to be scheduled on a node with the matching taint.
- **`containers`**: Defines an Nginx container to run inside the Pod.

Both the `nodeSelector` and the matching toleration are required to target this labeled, tainted worker node.

## Step 6: Deploy the Pod

Apply the manifest:

```bash
kubectl apply -f ivolve-app.yaml
```

Expected output:

```text
pod/ivolve-app created
```

## Step 7: Verify Pod Scheduling

Check the Pod and the node on which it is scheduled:

```bash
kubectl get pod ivolve-app -o wide
```

Observed output:

```text
NAME         READY   STATUS              RESTARTS   AGE   IP       NODE           NOMINATED NODE   READINESS GATES
ivolve-app   0/1     ContainerCreating   0          11s   <none>   minikube-m02   <none>           <none>
```

The output confirms that Kubernetes scheduled `ivolve-app` on `minikube-m02`.

The `ContainerCreating` status means the container was still being prepared when the command was executed. It does not necessarily indicate an error.

Check the Pod again to confirm that it becomes ready:

```bash
kubectl get pod ivolve-app -o wide
```

Expected final status:

```text
NAME         READY   STATUS    RESTARTS   AGE   IP            NODE
ivolve-app   1/1     Running   0          ...   <pod-ip>      minikube-m02
```

## Key Takeaways

| Kubernetes Feature | Purpose |
|---|---|
| `minikube node add` | Adds a worker node to the cluster |
| Taint | Restricts which Pods can be scheduled on a node |
| Label | Identifies a node using key-value metadata |
| `nodeSelector` | Selects nodes based on labels |
| Toleration | Allows a Pod to be scheduled despite a matching taint |
| `kubectl get pod -o wide` | Displays the node hosting the Pod |

**Result:** An additional worker node was added to Minikube, labeled with `project=ivolve`, and targeted for the `ivolve-app` Pod using a node selector and matching toleration.
