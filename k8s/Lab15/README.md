# Lab 15: Node.js Application Deployment with a ClusterIP Service

In this lab I deployed my custom Node.js image from Lab 8 on Kubernetes, connected it to the MySQL StatefulSet from Lab 14, and exposed it inside the cluster with a ClusterIP service.

## What the lab asked for

- A Deployment named `ivolveApp` in the `ivolve` namespace with 2 replicas, using my custom Docker Hub image from Lab 8
- Environment variables from the existing ConfigMap and Secret
- A node selector for nodes labeled `project=ivolve`
- A toleration for `workload=ivolveApp:NoSchedule`
- The PersistentVolume from Lab 13 mounted in the pods
- An init container with a MySQL client image that creates the `ivolve` database and grants the app user its privileges
- A ClusterIP service for the app
- Manual verification of the database and privileges, plus a check of the application logs

Kubernetes only accepts lowercase names, so I used `ivolveapp` for the Deployment and `ivolveappsvc` for the service.

## My setup

- Minikube on WSL with two nodes: `minikube` and `minikube-m02`
- Namespace `ivolve` with a quota of 3 pods (Lab 11)
- ConfigMap `ivolvecm` and Secret `ivolvesecret` (Lab 12)
- PVC `ivolvepvc` bound to PV `ivolvepv` at `/mnt/app-logs` (Lab 13)
- MySQL StatefulSet `ivolvedb` and headless service `ivolvedbsvc` (Lab 14)
- `minikube-m02` labeled `project=ivolve` and tainted `workload=ivolveApp:NoSchedule` (Lab 10)

## Step 1: Push the Lab 8 image to Docker Hub

Kubernetes pulls images from a registry, so the image from Lab 8 needs to be on Docker Hub under my username:

```bash
docker login
docker tag ivolveapp:latest ahmedshanab215/ivolveapp:latest
docker push ahmedshanab215/ivolveapp:latest
```

The push ends with a `digest: sha256:...` line. I also made sure the repository is **Public** on Docker Hub, so the cluster can pull it without credentials.

To find the port the app listens on:

```bash
docker inspect ivolveapp:latest --format '{{.Config.ExposedPorts}}'
```

It printed `map[3000/tcp:{}]`, so the container port is 3000.

## Step 2: Point `DB_HOST` at the database service

The ConfigMap from Lab 12 had `DB_HOST=mysql`. The MySQL headless service from Lab 14 is called `ivolvedbsvc`, so I updated the value:

```bash
kubectl patch cm ivolvecm -n ivolve --type merge -p '{"data":{"DB_HOST":"ivolvedbsvc"}}'
```

## Step 3: Write the Deployment

I saved this as `deployment.yaml` in the `Lab15` folder:

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ivolveapp
  namespace: ivolve
spec:
  replicas: 2
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 0
      maxUnavailable: 1
  selector:
    matchLabels:
      app: ivolveapp
  template:
    metadata:
      labels:
        app: ivolveapp
    spec:
      nodeSelector:
        project: ivolve
      tolerations:
        - key: workload
          operator: Equal
          value: ivolveApp
          effect: NoSchedule
      initContainers:
        - name: init-db
          image: mysql:8.0
          envFrom:
            - configMapRef:
                name: ivolvecm
            - secretRef:
                name: ivolvesecret
          command: ["sh", "-c"]
          args:
            - |
              until mysqladmin ping -h "$DB_HOST" -uroot -p"$MYSQL_ROOT_PASSWORD" --silent; do
                echo "waiting for MySQL..."; sleep 3
              done
              mysql -h "$DB_HOST" -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
                CREATE DATABASE IF NOT EXISTS ivolve;
                CREATE USER IF NOT EXISTS '$DB_USER'@'%' IDENTIFIED BY '$DB_PASSWORD';
                GRANT ALL PRIVILEGES ON ivolve.* TO '$DB_USER'@'%';
                FLUSH PRIVILEGES;"
      containers:
        - name: ivolveapp
          image: ahmedshanab215/ivolveapp:latest
          ports:
            - containerPort: 3000
          envFrom:
            - configMapRef:
                name: ivolvecm
            - secretRef:
                name: ivolvesecret
          volumeMounts:
            - name: logs
              mountPath: /app/logs
      volumes:
        - name: logs
          persistentVolumeClaim:
            claimName: ivolvepvc
```

How the pieces fit together:

- **`nodeSelector` and `tolerations`** send the pods to `minikube-m02`, the only node with the `project=ivolve` label, and let them past its taint.
- **`envFrom`** loads every key of `ivolvecm` and `ivolvesecret` as environment variables, in both the app container and the init container.
- **The init container** uses `mysql:8.0`, waits until MySQL answers, then logs in as root and creates the `ivolve` database and the app user, and grants that user all privileges on `ivolve`. It runs and finishes before the app container starts.
- **The volume** mounts the Lab 13 PVC `ivolvepvc` at `/app/logs`. I chose that path because the lab doesn't give one.
- **The rolling update settings** (`maxSurge: 0`, `maxUnavailable: 1`) replace pods one at a time. My namespace quota allows only 3 pods, so there's no room for an extra temporary pod.

## Step 4: Apply it and check where the pods landed

```bash
kubectl apply -f deployment.yaml
kubectl rollout status deployment/ivolveapp -n ivolve
kubectl get pods -n ivolve -o wide
```

Result:

```
NAME                       READY   STATUS    RESTARTS   AGE   IP           NODE
ivolveapp-7b87f6d8-27r7x   1/1     Running   0          17s   10.244.1.5   minikube-m02
ivolveapp-7b87f6d8-28rx4   1/1     Running   0          87s   10.244.1.4   minikube-m02
ivolvedb-0                 1/1     Running   0          12h   10.244.1.3   minikube-m02
```

Both app pods are on `minikube-m02`, so the node selector and toleration work.

## Step 5: Create the ClusterIP service

I generated the manifest with `-o yaml` so I'd have a file to keep, then applied it:

```bash
kubectl expose deployment ivolveapp --name=ivolveappsvc --port=80 --target-port=3000 --type=ClusterIP -n ivolve --dry-run=client -o yaml > service.yaml
kubectl apply -f service.yaml
kubectl get svc,endpoints -n ivolve
```

The generated `service.yaml` is essentially:

```yaml
apiVersion: v1
kind: Service
metadata:
  name: ivolveappsvc
  namespace: ivolve
spec:
  type: ClusterIP
  selector:
    app: ivolveapp
  ports:
    - port: 80
      targetPort: 3000
```

Result:

```
NAME                   TYPE        CLUSTER-IP      EXTERNAL-IP   PORT(S)
service/ivolveappsvc   ClusterIP   10.111.83.210   <none>        80/TCP

NAME                     ENDPOINTS
endpoints/ivolveappsvc   10.244.1.4:3000,10.244.1.5:3000
```

`expose` copies the Deployment's selector (`app=ivolveapp`), so I didn't have to write it by hand. The two endpoints are the two app pods, which is how the service spreads traffic across them. A deprecation warning about `v1 Endpoints` also appears. It's only a notice from newer Kubernetes versions.

## Step 6: Verify the init container and the database

The init container log:

```bash
POD=$(kubectl get pod -n ivolve -l app=ivolveapp -o jsonpath='{.items[0].metadata.name}')
kubectl logs -n ivolve $POD -c init-db
```

```
mysqladmin: [Warning] Using a password on the command line interface can be insecure.
mysqld is alive
mysql: [Warning] Using a password on the command line interface can be insecure.
```

`mysqld is alive` means it reached MySQL, and the SQL ran after that. The password warnings are only the MySQL client's notice about using `-p` on the command line.

Then I connected to MySQL manually as the application user. `SHOW GRANTS` with no user name shows the grants of whoever is logged in:

```bash
kubectl exec -it ivolvedb-0 -n ivolve -- sh -c 'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" -e "SHOW DATABASES; SHOW GRANTS;"'
```

```
+--------------------+
| Database           |
+--------------------+
| information_schema |
| ivolve             |
| performance_schema |
+--------------------+
+---------------------------------------------------------+
| Grants for ivolve_user@%                                |
+---------------------------------------------------------+
| GRANT USAGE ON *.* TO `ivolve_user`@`%`                 |
| GRANT ALL PRIVILEGES ON `ivolve`.* TO `ivolve_user`@`%` |
+---------------------------------------------------------+
```

So the `ivolve` database exists, and `ivolve_user` has all privileges on it.

## Step 7: Check the application logs

```bash
for p in $(kubectl get pod -n ivolve -l app=ivolveapp -o name); do echo "== $p"; kubectl logs -n ivolve $p -c ivolveapp --tail=3; done
```

```
== pod/ivolveapp-7b87f6d8-27r7x
🔄 Attempting to connect to MySQL...
✅ Connected to MySQL and 'ivolve' DB found.
🚀 Server started on http://0.0.0.0:3000
== pod/ivolveapp-7b87f6d8-28rx4
🔄 Attempting to connect to MySQL...
✅ Connected to MySQL and 'ivolve' DB found.
🚀 Server started on http://0.0.0.0:3000
```

Both pods connected to MySQL, found the `ivolve` database, and started the server.

## Step 8: Check the PersistentVolume and the service

To prove the Lab 13 volume is mounted, I wrote a file from inside the pod and read it from the node:

```bash
kubectl exec -n ivolve $POD -- sh -c 'echo hello > /app/logs/test.txt'
minikube ssh -n minikube-m02 -- cat /mnt/app-logs/test.txt
```

It printed `hello`, so the pod's `/app/logs` is the node's `/mnt/app-logs`. Then I removed the test file:

```bash
minikube ssh -n minikube-m02 -- sudo rm /mnt/app-logs/test.txt
```

To test the service, I called it from a temporary pod in the `default` namespace, because the `ivolve` namespace has no free pod slots:

```bash
kubectl run curl-test --image=curlimages/curl --rm -it --restart=Never -- curl -s http://ivolveappsvc.ivolve.svc.cluster.local
```

It returned the iVolve web page, so traffic reaches the app pods through the service's DNS name.

## Final result

| Requirement | Result |
|-------------|--------|
| Deployment `ivolveapp`, 2 replicas, custom Docker Hub image | 2 pods `1/1 Running` |
| Node selector `project=ivolve` | Pods run on `minikube-m02` |
| Toleration `workload=ivolveApp:NoSchedule` | Pods were admitted onto the tainted node |
| PersistentVolume from Lab 13 | File written in the pod appears in `/mnt/app-logs` on the node |
| Init container creates the database and grants privileges | `ivolve` exists and `ivolve_user` has `ALL PRIVILEGES` on it |
| Connection parameters from ConfigMap and Secret | Used by both the init container and the app |
| ClusterIP service `ivolveappsvc` | Cluster IP `10.111.83.210`, 2 endpoints on port 3000 |
| Manual MySQL check | Database and grants confirmed |
| Application logs | `Connected to MySQL and 'ivolve' DB found` on both pods |

## Notes

- The init container creates the user with `CREATE USER IF NOT EXISTS`, and the other statements are safe to repeat, so it can run again on every pod restart without errors.
- Both app pods run on the same node, so they share `/mnt/app-logs`. `hostPath` isn't shared storage across nodes.
- Pods read the ConfigMap and Secret only when they start. After changing either one, run `kubectl rollout restart deployment/ivolveapp -n ivolve`.
