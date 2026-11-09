// DeployOnK8s.groovy — Edit deployment file, push it to GitHub, deploy to cluster
// Lab 23: added the git commit + push step so Argo CD can pick up the change
def call(Map cfg = [:]) {
    def image     = cfg.image
    def namespace = cfg.namespace ?: 'ivolve'
    def manifest  = cfg.manifest ?: 'k8s/deployment.yaml'
    def gitRepo   = cfg.gitRepo ?: 'origin'

    stage('Edit Deployment File') {
        // regenerate the manifest client-side with the new image tag
        sh """
            kubectl create deployment demoapp \\
              --image=${image} \\
              --port=8080 \\
              --namespace=${namespace} \\
              --dry-run=client -o yaml > ${manifest}
        """
    }

    // ---- Lab 23: push the updated manifest so Argo CD sees it ----
    stage('Push Update to GitHub') {
        withCredentials([usernamePassword(credentialsId: 'github-creds',
                                          usernameVariable: 'GIT_USER',
                                          passwordVariable: 'GIT_PASS')]) {
            sh """
                git config user.email \"\${GIT_USER}@users.noreply.github.com\"
                git config user.name \"\${GIT_USER}\"
                git add ${manifest}
                git diff --cached --quiet || git commit -m \"ci: update demoapp image to ${image}\"
                git push ${gitRepo} HEAD
            """
        }
    }
    // --------------------------------------------------------------

    stage('Deploy to K8s') {
        sh 'kubectl apply -f k8s/namespace.yaml'
        sh "kubectl apply -f ${manifest}"
        sh 'kubectl apply -f k8s/service.yaml'
        sh "kubectl rollout status deployment/demoapp -n ${namespace} --timeout=120s"
    }
}
