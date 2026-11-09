// BuildApp.groovy — Unit test + Build app
def call(Map cfg = [:]) {
    def mavenArgs = cfg.skipTests ? '-DskipTests' : ''
    stage('Build App') {
        sh "mvn -B clean test package ${mavenArgs}"
    }
}
