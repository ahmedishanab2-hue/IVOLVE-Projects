# Lab 12: Managing Configuration and Sensitive Data with ConfigMaps and Secrets

## Objective

Separate application configuration from the container image by using Kubernetes **ConfigMaps** for non-sensitive data and **Secrets** for sensitive data, all inside the `ivolve` namespace.

## Resources Created

| Resource | Name | Namespace | Keys |
|----------|------|-----------|------|
| ConfigMap | `ivolvecm` | `ivolve` | `DB_HOST`, `DB_USER` |
| Secret | `ivolvesecret` | `ivolve` | `MYSQL_ROOT_PASSWORD`, `DB_PASSWORD` |

> **Note:** Kubernetes object names must be lowercase (DNS-1123), so `ivolveCM` and `ivolveSecret` from the lab statement were created as `ivolvecm` and `ivolvesecret`.

### ConfigMap values (non-sensitive)

| Key | Value | Description |
|-----|-------|-------------|
| `DB_HOST` | `mysql` | Name of the MySQL StatefulSet's headless Service |
| `DB_USER` | `ivolve_user` | Application database user |

### Secret values (sensitive, Base64-encoded)

| Key | Description |
|-----|-------------|
| `MYSQL_ROOT_PASSWORD` | Root password for the MySQL database |
| `DB_PASSWORD` | Password for the application user (`DB_USER`) |

## Prerequisites

- A running Kubernetes cluster and `kubectl` configured
- The `ivolve` namespace (already existing in this setup)

```bash
kubectl get namespaces
```

## Steps

### 1. Generate the ConfigMap manifest

```bash
kubectl create configmap ivolvecm -n ivolve \
  --from-literal=DB_HOST=mysql \
  --from-literal=DB_USER=ivolve_user \
  --dry-run=client -o yaml > ivolvecm.yaml
```

### 2. Generate the Secret manifest

`kubectl` Base64-encodes `--from-literal` values automatically into the `data:` section.

```bash
kubectl create secret generic ivolvesecret -n ivolve \
  --from-literal=MYSQL_ROOT_PASSWORD=root123 \
  --from-literal=DB_PASSWORD=ivolve123 \
  --dry-run=client -o yaml > ivolvesecret.yaml
```

### 3. Review the generated files

```bash
cat ivolvecm.yaml ivolvesecret.yaml
```

Generated `ivolvecm.yaml`:

```yaml
apiVersion: v1
data:
  DB_HOST: mysql
  DB_USER: ivolve_user
kind: ConfigMap
metadata:
  name: ivolvecm
  namespace: ivolve
```

Generated `ivolvesecret.yaml`:

```yaml
apiVersion: v1
data:
  DB_PASSWORD: aXZvbHZlMTIz
  MYSQL_ROOT_PASSWORD: cm9vdDEyMw==
kind: Secret
metadata:
  name: ivolvesecret
  namespace: ivolve
```

### 4. Apply the manifests

```bash
kubectl apply -f ivolvecm.yaml -f ivolvesecret.yaml
```

Expected output:

```
configmap/ivolvecm created
secret/ivolvesecret created
```

## Verification

```bash
# List both resources
kubectl get cm,secret -n ivolve

# Inspect the ConfigMap
kubectl describe cm ivolvecm -n ivolve

# Inspect the Secret (shows key names and sizes, not values)
kubectl describe secret ivolvesecret -n ivolve

# Decode a Secret value to confirm Base64 round-trip
kubectl get secret ivolvesecret -n ivolve -o jsonpath='{.data.DB_PASSWORD}' | base64 -d; echo
```

Expected: `ivolvecm` shows 2 keys, `ivolvesecret` shows 2 keys, and the decoded `DB_PASSWORD` prints `ivolve123`.

## Using These in Workloads

Inject everything at once with `envFrom`:

```yaml
envFrom:
  - configMapRef:
      name: ivolvecm
  - secretRef:
      name: ivolvesecret
```

Or reference individual keys:

```yaml
env:
  - name: DB_HOST
    valueFrom:
      configMapKeyRef:
        name: ivolvecm
        key: DB_HOST
  - name: DB_PASSWORD
    valueFrom:
      secretKeyRef:
        name: ivolvesecret
        key: DB_PASSWORD
```

When the MySQL StatefulSet is created in a later lab, its headless Service (`clusterIP: None`) must be named `mysql` to match `DB_HOST`.

## Security Notes

- Base64 is **encoding, not encryption**. Anyone with read access to the Secret can decode it.
- Do not commit real credentials or `ivolvesecret.yaml` to a public repository.
- For production, consider RBAC restrictions, encryption at rest, or an external secrets manager.

## Cleanup

```bash
kubectl delete -f ivolvecm.yaml -f ivolvesecret.yaml
```

## Files

```
Lab12/
├── ivolvecm.yaml        # ConfigMap manifest
├── ivolvesecret.yaml    # Secret manifest
└── README.md
```
