# Lab 11: Kubernetes Namespace and ResourceQuota

## Objective

- Create a Kubernetes namespace named `ivolve`.
- Create a ResourceQuota to limit the total number of Pods to three.
- Deploy Pods inside the namespace.
- Verify that Kubernetes rejects additional Pods when the quota is exceeded.

## Step 1: Generate the Namespace YAML

Generate the namespace manifest in the current working directory:

```bash
kubectl create namespace ivolve --dry-run=client -o yaml > namespace.yaml
```

This generates `namespace.yaml` without creating the namespace through this command.

Apply the manifest:

```bash
kubectl apply -f namespace.yaml
```

Verify the namespace:

```bash
kubectl get namespace ivolve
```

Output:

```text
NAME     STATUS   AGE
ivolve   Active   14s
```

## Step 2: Generate the ResourceQuota YAML

Create a ResourceQuota named `ivolvequota` in the `ivolve` namespace, with a maximum of three Pods:

```bash
kubectl create quota ivolvequota \
  --namespace=ivolve \
  --hard=pods=3 \
  --dry-run=client -o yaml > resourcequota.yaml
```

**Note:** Kubernetes resource names must be lowercase, so `ivolvequota` is used instead of `ivolveQuota`.

Apply the ResourceQuota:

```bash
kubectl apply -f resourcequota.yaml
```

Verify the quota:

```bash
kubectl describe resourcequota ivolvequota -n ivolve
```

Output before creating any Pods:

```text
Name:       ivolvequota
Namespace:  ivolve
Resource    Used  Hard
--------    ----  ----
pods        0     3
```

## Step 3: Create Pods in the Namespace

Create the first Pod:

```bash
kubectl run my-pod --image=nginx --namespace=ivolve
```

Create the second Pod:

```bash
kubectl run my-p0od --image=nginx --namespace=ivolve
```

Create the third Pod:

```bash
kubectl run my-p0aod --image=nginx --namespace=ivolve
```

These commands successfully created three Pods in the namespace.

## Step 4: Test the ResourceQuota Limit

Attempt to create a fourth Pod:

```bash
kubectl run my-pp0aod --image=nginx --namespace=ivolve
```

Expected error:

```text
Error from server (Forbidden): pods "my-pp0aod" is forbidden:
exceeded quota: ivolvequota, requested: pods=1,
used: pods=3, limited: pods=3
```

**Explanation:**

- `requested: pods=1`: The new Pod requires one additional Pod slot.
- `used: pods=3`: Three Pods already exist in the namespace.
- `limited: pods=3`: The maximum permitted number of Pods is three.

Kubernetes rejects the fourth Pod because creating it would exceed the configured quota.

## Step 5: Verify the Final Quota Usage

Run:

```bash
kubectl describe resourcequotas ivolvequota -n ivolve
```

Final output:

```text
Name:       ivolvequota
Namespace:  ivolve
Resource    Used  Hard
--------    ----  ----
pods        3     3
```

The quota is fully consumed: three Pods are counted against a maximum of three.

You can also list the Pods:

```bash
kubectl get pods -n ivolve
```

## Troubleshooting

An incorrect namespace name, such as `ivlove`, results in a `NotFound` error:

```bash
kubectl describe resourcequotas ivolvequota -n ivlove
```

Use the correct namespace name:

```bash
kubectl describe resourcequotas ivolvequota -n ivolve
```

## Key Takeaways

| Concept | Purpose |
|---|---|
| Namespace | Separates Kubernetes resources into logical groups |
| ResourceQuota | Limits aggregate resource consumption within a namespace |
| `--dry-run=client` | Generates or previews a resource without creating it through that command |
| `-o yaml` | Outputs a Kubernetes resource manifest in YAML format |
| `kubectl describe resourcequota` | Displays quota limits and current usage |

**Result:** The `ivolve` namespace was created, the `ivolvequota` ResourceQuota was configured with a limit of three Pods, and a fourth Pod creation attempt was rejected as expected.

