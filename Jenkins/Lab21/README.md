# Lab 21: Jenkins Pipeline for Application Deployment

## Objective

Clone [DemoApp](https://github.com/Ibrahim-Adel15/DemoApp.git) and create a Jenkins pipeline that automates: build → Docker image → scan → push → delete local image → update deployment file → deploy to Kubernetes, with `post` actions for always/success/failure.

## What the lab asked for

| # | Stage |
|---|-------|
| 1 | Build app |
| 2 | Build Docker image |
| 3 | Scan Docker image |
| 4 | Push image to DockerHub |
| 5 | Delete image locally |
| 6 | Update image in deployment file |
| 7 | Deploy to Kubernetes cluster |

Plus: pipeline `post` actions (`always`, `success`, `failure`).

## My setup

- Jenkins with plugins: **Pipeline**, **Docker**, **Kubernetes CLI**
- Tools on the Jenkins agent: `mvn`, `docker`, `trivy`, `kubectl`
- Credentials in Jenkins:
  - `dockerhub-creds` (username/password) for DockerHub push
  - kubeconfig for the cluster (Kubernetes CLI plugin)
- Cluster already has the node label/taint from Labs 10–14 if you schedule there (this lab uses the `ivolve` namespace)

## Step 1: Generate the Kubernetes manifests with --dry-run

Every manifest in [`k8s/`](./k8s/) was generated **client-side** (no cluster needed) using `--dry-run=client -o yaml`, so they can be committed and pushed to GitHub:

```bash
mkdir -p k8s

kubectl create namespace ivolve \
  --dry-run=client -o yaml > k8s/namespace.yaml

kubectl create deployment demoapp \
  --image=ahmedshanab215/demoapp:latest \
  --port=8080 \
  --dry-run=client -o yaml > k8s/deployment.yaml

kubectl create service clusterip demoapp \
  --tcp=8080:8080 \
  --dry-run=client -o yaml > k8s/service.yaml
```

Files created:

```text
k8s/namespace.yaml     # namespace ivolve
k8s/deployment.yaml    # deployment demoapp, port 8080
k8s/service.yaml       # ClusterIP 8080 -> 8080
```

## Step 2: Clone DemoApp and add the Jenkinsfile

```bash
git clone https://github.com/Ibrahim-Adel15/DemoApp.git
cd DemoApp
```

Copy the [`Jenkinsfile`](./Jenkinsfile) into the root of DemoApp (next to `pom.xml` and `Dockerfile`).

## Step 3: Create the pipeline job in Jenkins

```text
New Item → Pipeline → name: demoapp-pipeline → OK
```

Under **Pipeline**:

- Definition: **Pipeline script from SCM**
- SCM: Git → `https://github.com/Ibrahim-Adel15/DemoApp.git`
- Script Path: `Jenkinsfile`

Save.

## Step 4: What each stage does

### Stage 1 — Build app

```groovy
sh 'mvn -B clean package -DskipTests'
```

Produces `target/demo-0.0.1-SNAPSHOT.jar` (the file the Dockerfile `COPY`s).

### Stage 2 — Build Docker image

```groovy
sh 'docker build -t $FULL_IMAGE .'
```

Tag format: `ahmedshanab215/demoapp:<build_number>`.

### Stage 3 — Scan Docker image

```groovy
sh 'trivy image --severity HIGH,CRITICAL --exit-code 0 $FULL_IMAGE'
```

Prints HIGH/CRITICAL findings without failing the build (`--exit-code 0`).

### Stage 4 — Push to DockerHub

Uses the `dockerhub-creds` credential, logs in, pushes the image.

### Stage 5 — Delete image locally

```groovy
sh 'docker rmi $FULL_IMAGE || true'
```

Frees disk space on the agent; the image stays on DockerHub.

### Stage 6 — Update image in deployment file

Regenerates the deployment manifest with the **new tag**, still client-side:

```bash
kubectl create deployment demoapp \
  --image=$FULL_IMAGE \
  --port=8080 \
  --namespace=$KUBE_NS \
  --dry-run=client -o yaml > k8s/deployment.yaml
```

### Stage 7 — Deploy to Kubernetes

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml
kubectl rollout status deployment/demoapp -n ivolve --timeout=120s
```

## Step 5: post actions

```groovy
post {
    always  { echo 'Pipeline finished (always).'; cleanWs() }
    success { echo "SUCCESS: $FULL_IMAGE built, scanned, pushed and deployed." }
    failure { echo 'FAILURE: check the logs.' }
}
```

| Action | When |
|--------|------|
| `always`   | Every run — cleanup workspace |
| `success`  | All stages green — report image + deploy OK |
| `failure`  | Any stage failed — point to the failing stage |

## Step 6: Run and verify

Click **Build Now**, then check:

```bash
# image on DockerHub
# https://hub.docker.com/r/ahmedshanab215/demoapp

# deployment picked up the new tag
kubectl get deploy demoapp -n ivolve -o jsonpath='{.spec.template.spec.containers[0].image}'; echo

# app reachable
kubectl get svc demoapp -n ivolve
kubectl run curl --rm -it --image=curlimages/curl -- -s http://demoapp:8080/
```

Expected body: `Hello from Dockerized Spring Boot!`

## Result

| Requirement | Result |
|-------------|--------|
| Clone DemoApp | ✅ |
| Pipeline: build → image → scan → push → delete → update → deploy | ✅ 7 stages |
| Manifests via `--dry-run=client -o yaml` | ✅ `k8s/*.yaml` |
| `post`: always / success / failure | ✅ |
| Deployed to Kubernetes (`ivolve` ns) | ✅ |
