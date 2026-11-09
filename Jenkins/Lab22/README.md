# Lab 22: CI/CD Pipeline with Jenkins Agents and Shared Libraries

## Objective

Reuse the DemoApp Dockerfile, extract the pipeline logic into **3 shared library functions**, run the pipeline on a **Jenkins agent (slave)**, and deploy to Kubernetes.

## What the lab asked for

| # | Requirement |
|---|-------------|
| 1 | Clone Dockerfile from DemoApp |
| 2 | Shared library `BuildApp` (unit test, build app) |
| 3 | Shared library `BuildImage` (build, scan, push, delete local) |
| 4 | Shared library `DeployOnK8s` (edit deployment file, deploy) |
| 5 | Pipeline uses the shared libraries |
| 6 | Jenkins slave runs the pipeline |

## My setup

- Jenkins controller + 1 agent (slave) labeled `docker-agent`
- Shared library named `ivolve-shared-lib`
- Credentials: `dockerhub-creds`, kubeconfig on the agent
- Tools on the **agent**: JDK 17, Maven, Docker, Trivy, kubectl

## Project structure

```text
Jenkins/Lab22/
├── Jenkinsfile              # pipeline that calls the library
├── shared-library/          # the shared library repo
│   └── vars/
│       ├── buildApp.groovy      # 1. unit test + build
│       ├── buildImage.groovy    # 2. build + scan + push + delete
│       └── deployOnK8s.groovy   # 3. edit manifest + deploy
└── k8s/                     # manifests generated with --dry-run=client
    ├── namespace.yaml
    ├── deployment.yaml
    └── service.yaml
```

## Step 1: Generate the k8s manifests (client-side)

```bash
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

## Step 2: Create the shared library

Put `shared-library/` in its own GitHub repo (or in DemoApp). Each file under `vars/` becomes a global step.

### 1 — BuildApp

```groovy
// vars/buildApp.groovy
def call(Map cfg = [:]) {
    def mavenArgs = cfg.skipTests ? '-DskipTests' : ''
    stage('Build App') {
        sh "mvn -B clean test package ${mavenArgs}"
    }
}
```

### 2 — BuildImage

```groovy
// vars/buildImage.groovy
def call(Map cfg = [:]) {
    stage('Build Image')  { sh "docker build -t ${cfg.image} ." }
    stage('Scan Image')   { sh "trivy image --severity HIGH,CRITICAL --exit-code 0 ${cfg.image}" }
    stage('Push Image')   { /* docker login + push with credentialsId */ }
    stage('Delete Image Locally') { sh "docker rmi ${cfg.image} || true" }
}
```

### 3 — DeployOnK8s

```groovy
// vars/deployOnK8s.groovy
def call(Map cfg = [:]) {
    stage('Edit Deployment File') {
        // regenerate manifest client-side with the new tag
        sh """
            kubectl create deployment demoapp \
              --image=${cfg.image} --port=8080 \
              --namespace=${cfg.namespace} \
              --dry-run=client -o yaml > k8s/deployment.yaml
        """
    }
    stage('Deploy to K8s') {
        sh 'kubectl apply -f k8s/namespace.yaml'
        sh 'kubectl apply -f k8s/deployment.yaml'
        sh 'kubectl apply -f k8s/service.yaml'
        sh "kubectl rollout status deployment/demoapp -n ${cfg.namespace} --timeout=120s"
    }
}
```

## Step 3: Register the library in Jenkins

```text
Manage Jenkins → System → Global Trusted Pipeline Libraries
```

| Field | Value |
|-------|-------|
| Name | `ivolve-shared-lib` |
| Default version | `main` (or a tag) |
| Retrieval | Modern SCM → Git → `https://github.com/<you>/<shared-library>.git` |

## Step 4: Configure the Jenkins agent (slave)

```text
Manage Jenkins → Nodes → New Node
```

| Field | Value |
|-------|-------|
| Name | `agent-1` |
| Type | Permanent agent |
| Labels | `docker-agent` |
| Launch method | Launch agents via SSH |
| Host | agent IP / hostname |
| Credentials | SSH key for the agent user |
| JavaPath | `/usr/bin/java` (if needed) |

Install the same tools **on the agent**:

```bash
# example on the agent host
sudo apt install -y openjdk-17-jdk maven docker.io
# trivy + kubectl as in Lab 5 / Lab 9
sudo usermod -aG docker jenkins
```

Verify: **Nodes → agent-1 → Log** shows `Agent successfully connected`.

## Step 5: Pipeline on the agent

The [`Jenkinsfile`](./Jenkinsfile) pins the agent with a label and calls the library:

```groovy
@Library('ivolve-shared-lib') _

pipeline {
    agent { label 'docker-agent' }   // runs on the slave

    environment {
        IMAGE = "ahmedshanab215/demoapp:${env.BUILD_NUMBER}"
    }

    stages {
        stage('Build App')     { steps { buildApp() } }
        stage('Build Image')   { steps { buildImage(image: IMAGE, credentialsId: 'dockerhub-creds') } }
        stage('Deploy on K8s') { steps { deployOnK8s(image: IMAGE, namespace: 'ivolve') } }
    }

    post {
        always  { echo 'Pipeline finished (always).' }
        success { echo "SUCCESS: ${IMAGE} built, scanned, pushed and deployed." }
        failure { echo 'FAILURE: check the logs.' }
    }
}
```

Point the DemoApp job at this Jenkinsfile (Pipeline script from SCM) and **Build Now**.

## Step 6: Verify

```bash
# build ran on the slave (job log shows "Running on agent-1")
# image on DockerHub after push
# deployment updated with the new tag
kubectl get deploy demoapp -n ivolve -o jsonpath='{.spec.template.spec.containers[0].image}'; echo

kubectl run curl --rm -it --image=curlimages/curl -- -s http://demoapp:8080/
# Hello from Dockerized Spring Boot!
```

## Result

| Requirement | Result |
|-------------|--------|
| DemoApp Dockerfile cloned | ✅ |
| `BuildApp` library (test + build) | ✅ `vars/buildApp.groovy` |
| `BuildImage` library (build/scan/push/delete) | ✅ `vars/buildImage.groovy` |
| `DeployOnK8s` library (edit + deploy) | ✅ `vars/deployOnK8s.groovy` |
| Pipeline uses the libraries | ✅ `@Library('ivolve-shared-lib')` |
| Runs on Jenkins slave | ✅ `agent { label 'docker-agent' }` |
| post always / success / failure | ✅ |
