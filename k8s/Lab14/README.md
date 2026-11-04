# Lab 14: Running MySQL as a StatefulSet with a Headless Service

In this lab I deployed a MySQL database on Kubernetes the "proper" way for stateful apps: a StatefulSet with its own storage and a headless service, so the pod gets a stable name and keeps its data even if it gets restarted.

## What the lab asked for

- A StatefulSet called `ivolveDB` in the `ivolve` namespace, 1 replica, running MySQL
- Environment variables coming from the ConfigMap and Secret I made in Lab 12
- The pod must land on the node labeled `project=ivolve`
- A toleration for `workload=ivolveApp:NoSchedule`
- A PVC template mounted at `/var/lib/mysql`
- A headless service called `ivolveDBSvc`
- Proof that MySQL works and that the pod's DNS name resolves inside the cluster

One small thing: Kubernetes only accepts lowercase names, so I used `ivolvedb` and `ivolvedbsvc` instead of `ivolveDB` and `ivolveDBSvc`.

## My setup

I'm running Minikube on WSL with two nodes, `minikube` and `minikube-m02`. From the earlier labs I already had:

- the `ivolve` namespace with a quota of 3 pods (Lab 11)
- the `ivolvecm` ConfigMap and `ivolvesecret` Secret (Lab 12)
- `minikube-m02` labeled `project=ivolve` and tainted with `workload=ivolveApp:NoSchedule` (Lab 10)

## Step 1: Check everything is in place first

Before writing any YAML, I checked that the things the StatefulSet depends on actually exist:

```bash
kubectl get cm,secret -n ivolve
kubectl get nodes --show-labels | grep project
kubectl describe node minikube-m02 | grep -i taint
kubectl describe quota ivolvequota -n ivolve
```

The quota part matters. My namespace only allows 3 pods, so I needed one free slot or the new pod would get rejected.

I also looked at the key names inside the ConfigMap and Secret, because the manifest has to match them exactly:

```bash
kubectl get cm ivolvecm -n ivolve -o yaml
kubectl describe secret ivolvesecret -n ivolve
```

- ConfigMap: `DB_HOST` and `DB_USER` (mine is `ivolve_user`)
- Secret: `MYSQL_ROOT_PASSWORD` and `DB_PASSWORD`

Make sure `DB_USER` isn't `root`. MySQL refuses to create a normal user called root, and the pod will crash on startup.

## Step 2: Write the manifest

I put the service and the StatefulSet in one file called `statefulset.yaml`:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: ivolvedbsvc
  namespace: ivolve
spec:
  clusterIP: None
  selector:
    app: ivolvedb
  ports:
    - name: mysql
      port: 3306
      targetPort: 3306
---
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: ivolvedb
  namespace: ivolve
spec:
  serviceName: ivolvedbsvc
  replicas: 1
  selector:
    matchLabels:
      app: ivolvedb
  template:
    metadata:
      labels:
        app: ivolvedb
    spec:
      nodeSelector:
        project: ivolve
      tolerations:
        - key: workload
          operator: Equal
          value: ivolveApp
          effect: NoSchedule
      containers:
        - name: mysql
          image: mysql:8.0
          ports:
            - containerPort: 3306
              name: mysql
          envFrom:
            - configMapRef:
                name: ivolvecm
            - secretRef:
                name: ivolvesecret
          env:
            - name: MYSQL_DATABASE
              value: ivolve
            - name: MYSQL_USER
              valueFrom:
                configMapKeyRef:
                  name: ivolvecm
                  key: DB_USER
            - name: MYSQL_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: ivolvesecret
                  key: DB_PASSWORD
          volumeMounts:
            - name: data
              mountPath: /var/lib/mysql
  volumeClaimTemplates:
    - metadata:
        name: data
      spec:
        accessModes:
          - ReadWriteOnce
        resources:
          requests:
            storage: 1Gi
```

A few things worth explaining:

- **Why the extra `env` entries?** The MySQL image doesn't know about `DB_USER` or `DB_PASSWORD`. It looks for `MYSQL_USER` and `MYSQL_PASSWORD`, so I map those two from the ConfigMap and Secret by hand. `MYSQL_ROOT_PASSWORD` already has the right name, so `envFrom` picks it up directly.
- **Why `clusterIP: None`?** That's what makes the service headless. Instead of giving one virtual IP, DNS points straight at the pod.
- **Why `volumeClaimTemplates`?** The StatefulSet creates a PVC for each replica automatically (here `data-ivolvedb-0`), and reattaches it if the pod is recreated. That's how the data survives.

## Step 3: Apply it

```bash
kubectl apply -f statefulset.yaml
```

You should see:

```
service/ivolvedbsvc created
statefulset.apps/ivolvedb created
```

> My first attempt failed with `the path "statefulset.yaml" does not exist`. The file hadn't actually been saved in my `Lab14` folder. Running `ls -l statefulset.yaml` before applying saves a lot of confusion.

## Step 4: Wait for the pod

```bash
kubectl get pods -n ivolve -o wide -w
kubectl get pvc -n ivolve
```

For about a minute and a half the pod sat in `ContainerCreating`. That's normal: the `mysql:8.0` image has to be pulled onto the worker node the first time. Once it showed `1/1 Running` I pressed `Ctrl+C` to stop watching.

What I confirmed:

- `ivolvedb-0` is running on `minikube-m02`, so the node selector and toleration work
- `data-ivolvedb-0` is `Bound` (1Gi, `RWO`), provisioned by Minikube's default `standard` StorageClass

I also saw a `FailedScheduling ... unbound immediate PersistentVolumeClaims` event. It looks scary, but it only means the scheduler checked a moment before the volume was ready. The pod was scheduled right after, so I ignored it.

## Step 5: Check that the database actually works

First I looked at the logs to make sure MySQL had finished starting up:

```bash
kubectl logs ivolvedb-0 -n ivolve | tail -5
```

The last line should say `ready for connections`. If you try `exec` before that, you may get `container not found` or a socket error. Just wait a bit and try again.

Then I connected with the MySQL client, first as root:

```bash
kubectl exec -it ivolvedb-0 -n ivolve -- sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW DATABASES; SELECT VERSION();"'
```

and then as the application user:

```bash
kubectl exec -it ivolvedb-0 -n ivolve -- sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -e "SELECT CURRENT_USER(); SHOW DATABASES;"'
```

What I got:

- Root can see `information_schema`, `ivolve`, `mysql`, `performance_schema` and `sys`, and the version is 8.0.46
- The app user shows up as `ivolve_user@%` and can see the `ivolve` database

The "using a password on the command line interface can be insecure" message is just a warning from the client. I used `$MYSQL_ROOT_PASSWORD` from the pod's environment so I never had to type the password.

## Step 6: Check DNS inside the cluster

Each StatefulSet pod gets a stable DNS name: `ivolvedb-0.ivolvedbsvc.ivolve.svc.cluster.local`.

My `ivolve` namespace was already at its 3-pod limit, so I ran the test client in the `default` namespace:

```bash
kubectl run dns-test --image=busybox:1.28 --restart=Never --rm -it -- nslookup ivolvedbsvc.ivolve.svc.cluster.local
kubectl run dns-test --image=busybox:1.28 --restart=Never --rm -it -- nslookup ivolvedb-0.ivolvedbsvc.ivolve.svc.cluster.local
```

Both returned the pod's IP:

```
Name:      ivolvedbsvc.ivolve.svc.cluster.local
Address 1: 10.244.1.3 ivolvedb-0.ivolvedbsvc.ivolve.svc.cluster.local
```

That matches `kubectl get pod ivolvedb-0 -n ivolve -o wide`. It's also the proof that the service is headless: a normal service would return a ClusterIP, but this one hands back the pod's address directly.

Two small notes:

- You'll see `couldn't attach to pod/dns-test, falling back to streaming logs`. It's harmless. The output still prints.
- If you get `pods "dns-test" already exists`, an old test pod is still hanging around. Remove it with `kubectl delete pod dns-test --force --grace-period=0` and rerun.

As a last end-to-end check, I connected to MySQL using the DNS name instead of localhost:

```bash
kubectl exec -it ivolvedb-0 -n ivolve -- sh -c 'mysql -h ivolvedb-0.ivolvedbsvc.ivolve.svc.cluster.local -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SELECT 1;"'
```

It returned `1`, so the name resolves and MySQL answers on it.

## Step 7: Final check

```bash
kubectl get statefulset,svc,pvc -n ivolve
kubectl get pod ivolvedb-0 -n ivolve -o wide
kubectl get svc ivolvedbsvc -n ivolve -o jsonpath='{.spec.clusterIP}{"\n"}'
```

Everything lined up:

- StatefulSet `ivolvedb` is `1/1`
- Service `ivolvedbsvc` has CLUSTER-IP `None`
- PVC `data-ivolvedb-0` is `Bound`
- The pod runs on `minikube-m02`
- The jsonpath command prints `None`

## Things that can go wrong

| What you see | Why it happens | What to do |
|--------------|----------------|------------|
| `the path "statefulset.yaml" does not exist` | The file wasn't saved in the current folder | Check with `ls -l` and recreate it |
| `container not found ("mysql")` | The container isn't running yet, usually still pulling the image | Wait for `1/1 Running`, then retry |
| `Can't connect to local MySQL server through socket` | MySQL is still initializing | Wait 20 to 30 seconds and retry |
| Pod `Pending`, "didn't match node selector" | No node has the `project=ivolve` label | `kubectl label node minikube-m02 project=ivolve` |
| Pod `Pending`, "untolerated taint" | Toleration doesn't match the taint | Match `workload=ivolveApp` exactly, including capital letters |
| Pod `Pending`, "exceeded quota" | `ivolvequota` only allows 3 pods | Delete a pod you don't need in `ivolve` |
| `CrashLoopBackOff` | A missing key in the ConfigMap or Secret, or `DB_USER` is `root` | Look at `kubectl logs ivolvedb-0 -n ivolve` |

## What I learned

- A headless service doesn't load-balance. It gives each pod its own DNS name, which is exactly what a database needs.
- `volumeClaimTemplates` is how StatefulSets get their own storage. Deleting the pod doesn't delete the data, because the new pod reattaches the same PVC.
- This lab used Minikube's dynamic provisioning, so the PV from Lab 13 wasn't involved.
- `DB_HOST` in my ConfigMap still says `mysql`. MySQL itself ignores that variable, so the lab works either way, but changing it to `ivolvedbsvc` would make it match the service.
