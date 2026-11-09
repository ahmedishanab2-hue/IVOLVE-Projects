// BuildImage.groovy — Build, scan, push, delete image locally
def call(Map cfg = [:]) {
    def image    = cfg.image
    def credsId  = cfg.credentialsId ?: 'dockerhub-creds'
    def severity = cfg.severity ?: 'HIGH,CRITICAL'

    stage('Build Image') {
        sh "docker build -t ${image} ."
    }

    stage('Scan Image') {
        sh "trivy image --severity ${severity} --exit-code 0 ${image}"
    }

    stage('Push Image') {
        withCredentials([usernamePassword(credentialsId: credsId,
                                          usernameVariable: 'DOCKER_USER',
                                          passwordVariable: 'DOCKER_PASS')]) {
            sh """
                echo \$DOCKER_PASS | docker login -u \$DOCKER_USER --password-stdin
                docker push ${image}
            """
        }
    }

    stage('Delete Image Locally') {
        sh "docker rmi ${image} || true"
    }
}
