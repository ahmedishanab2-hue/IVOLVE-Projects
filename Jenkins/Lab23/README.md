# Lab 23: Implementing a GitOps Workflow with Jenkins and Argo CD

## What the lab asked for

- Install Argo CD in the cluster and log into the UI
- Connect Argo CD to a GitHub repo
- Create an Application named `ivovle` that watches a manifest directory
- Turn on automatic sync
- Change the `DeployOnK8s` shared library so it **pushes the edited deployment file back to GitHub**
- Run the Jenkins pipeline → new image → Argo CD notices and syncs by itself

## My setup

- Minikube cluster (same one from Labs 9–18), namespace `ivolve`
- Jenkins with the shared library from Lab 22 (`ivolve-shared-lib`)
- GitHub repo: `ahmedishanab2-hue/IVOLVE-Projects` — manifests live in `Jenkins/Lab23/k8s/`
- Credentials in Jenkins: `dockerhub-creds`, `github-creds` (username + PAT with `repo` scope)

---

## Step 1: Install Argo CD

I installed Argo CD with the official manifests (no Helm needed):

```bash
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

Waited for everything to come up:

```bash
kubectl get pods -n argocd
```

```text
NAME                                      READY   STATUS    RESTARTS   AGE
argocd-applicationset-controller-...      1/1     Running   0          3m
argocd-dex-server-...                     1/1     Running   0          3m
argocd-notifications-controller-...       1/1     Running   0          3m
argocd-repo-server-...                    1/1     Running   0          3m
argocd-server-...                         1/1     Running   0          3m
```

All green, so I moved on.

## Step 2: Log into the UI

The server is not exposed by default, so I port-forwarded it:

```bash
kubectl port-forward svc/argocd-server -n argocd 8081:443 &
```

Opened `https://localhost:8081` in the browser (cert warning → Advanced → Proceed — it's self-signed).

Username: `admin`

Password — Argo CD generates it, I grabbed it with:

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath='{.data.password}' | base64 -d; echo
```

Pasted it into the login form and I was in. The UI showed an empty "Applications" page.

For the CLI later I also logged in:

```bash
argocd login localhost:8081 --username admin --password '<the password above>' --insecure
```

## Step 3: Connect Argo CD to GitHub

Argo CD needs permission to read the repo. I added it with the CLI:

```bash
argocd repo add https://github.com/ahmedishanab2-hue/IVOLVE-Projects.git \
  --username ahmedishanab2-hue \
  --password '<github PAT>'
```

```text
Repository 'https://github.com/ahmedishanab2-hue/IVOLVE-Projects.git' added
```

(You can also do this in the UI: **Settings → Repositories → Connect Repo using GitHub**.)

## Step 4: Create the Application `ivovle`

The manifest directory Argo CD should watch is `Jenkins/Lab23/k8s` — it holds the three files I've been generating with `--dry-run=client -o yaml`:

```text
k8s/namespace.yaml
k8s/deployment.yaml
k8s/service.yaml
```

I created the Application from the file I saved here: [`argocd/application.yaml`](./argocd/application.yaml)

```bash
kubectl apply -f argocd/application.yaml
```

Important bits of the spec:

```yaml
metadata:
  name: ivovle          # the name the lab asked for
spec:
  source:
    repoURL: https://github.com/ahmedishanab2-hue/IVOLVE-Projects.git
    targetRevision: main
    path: Jenkins/Lab23/k8s          # directory to watch
  destination:
    server: https://kubernetes.default.svc
    namespace: ivolve
  syncPolicy:
    automated:            # <-- automatic sync
      prune: true
      selfHeal: true
```

## Step 5: Turn on automatic sync

I didn't touch anything else — `syncPolicy.automated` in the YAML **is** the auto-sync switch:

- `prune: true` → objects removed from Git get deleted from the cluster
- `selfHeal: true` → if someone changes the cluster by hand, Argo CD rolls it back

In the UI you can see it under the app → **App Details → Sync Policy → Automatic**.

Checked the app status:

```bash
argocd app get ivovle
```

```text
Name:               ivovle
Sync Policy:        <none><auto (Prune,Self Heal)>
Health Status:      Healthy
```

## Step 6: Modify DeployOnK8s to push the edited file

The whole point of GitOps: **Git is the only source of truth**, so Jenkins must not just apply the file — it has to **commit the new image tag back to GitHub**.

I updated the shared library step (full file: [`deployOnK8s.groovy`](./deployOnK8s.groovy)) and added one stage between "Edit Deployment File" and "Deploy to K8s":

```groovy
stage('Push Update to GitHub') {
    withCredentials([usernamePassword(credentialsId: 'github-creds',
                                      usernameVariable: 'GIT_USER',
                                      passwordVariable: 'GIT_PASS')]) {
        sh """
            git config user.email \"\${GIT_USER}@users.noreply.github.com\"
            git config user.name \"\${GIT_USER}\"
            git add k8s/deployment.yaml
            git diff --cached --quiet || git commit -m \"ci: update demoapp image to ${image}\"
            git push origin HEAD
        """
    }
}
```

Notes:

- `git diff --cached --quiet ||` → only commits when something actually changed (no empty commits)
- `github-creds` is a Jenkins username/password credential; the password is a **PAT** with `repo` scope
- I put the updated file into the shared library repo and bumped the library version so the job picks it up

## Step 7: Trigger the Jenkins pipeline

Clicked **Build Now** on the DemoApp pipeline. Watched the stages:

| Stage | Result |
|-------|--------|
| Build App | `BUILD SUCCESS` |
| Build Image | `ahmedshanab215/demoapp:<N>` built |
| Scan Image | trivy output (HIGH/CRITICAL listed) |
| Push Image | pushed to DockerHub |
| Delete Image Locally | removed from agent |
| **Push Update to GitHub** | `ci: update demoapp image to ahmedshanab215/demoapp:<N>` pushed |
| Deploy to K8s | rollout complete |

I checked the commit on GitHub — `k8s/deployment.yaml` now had the new tag.

## Step 8: Confirm Argo CD picked it up

Opened the UI a few seconds later (argocd polls every ~3 min by default, but the webhook makes it instant; I didn't set the webhook, so I just waited / hit **Refresh**):

```text
ivovle  ▲ Synced  Healthy
```

The **Resource History** showed a new operation with the commit message from Jenkins, and:

```bash
kubectl get deploy demoapp -n ivolve \
  -o jsonpath='{.spec.template.spec.containers[0].image}'; echo
```

```text
ahmedshanab215/demoapp:42
```

matched the tag Jenkins had just pushed. So:

1. Jenkins built the image and rewrote the manifest
2. Jenkins pushed the manifest to GitHub
3. Argo CD noticed the new commit
4. Argo CD applied it to the cluster — **without anyone running kubectl apply by hand**

---

## The flow (how it all fits together)

```text
git push / commit
      │
      ▼
┌─────────────┐   build, scan, push image   ┌──────────────┐
│   GitHub    │◄──── commit new tag ────────│   Jenkins    │
│  k8s/*.yaml │                            │  (agent-1)   │
└──────┬──────┘                            └──────────────┘
       │ watches path Jenkins/Lab23/k8s
       ▼
┌─────────────┐   automated sync           ┌──────────────┐
│   Argo CD   │───────────────────────────►│  Kubernetes  │
│  app:ivovle │                            │ ns: ivolve   │
└─────────────┘                            └──────────────┘
```

## Result

| Requirement | Result |
|-------------|--------|
| Argo CD installed | ✅ pods Running in `argocd` ns |
| UI login works | ✅ admin + initial-admin password |
| GitHub repo connected | ✅ `IVOLVE-Projects.git` added |
| Application named `ivovle` | ✅ `argocd/application.yaml` |
| Watches repo + manifest dir | ✅ path `Jenkins/Lab23/k8s` on `main` |
| Automatic sync | ✅ `syncPolicy.automated` (prune + selfHeal) |
| DeployOnK8s pushes updated file to GitHub | ✅ new `Push Update to GitHub` stage |
| Jenkins builds & publishes new image | ✅ pipeline run |
| Argo CD detects + synchronizes | ✅ app Synced, pod image matches new tag |
