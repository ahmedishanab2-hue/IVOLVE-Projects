// DeployOnK8s.groovy — Edit deployment file + deploy to cluster
def call(Map cfg = [:]) {
    def image     = cfg.image
    def namespace = cfg.namespace ?: 'ivolve'
    def manifest  = cfg.manifest ?: 'k8s/deployment.yaml'

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

    stage('Deploy to K8s') {
        sh 'kubectl apply -f k8s/namespace.yaml'
        sh "kubectl apply -f ${manifest}"
        sh 'kubectl apply -f k8s/service.yaml'
        sh "kubectl rollout status deployment/demoapp -n ${namespace} --timeout=120s"
    }
}
