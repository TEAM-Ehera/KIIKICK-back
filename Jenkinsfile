pipeline {
    agent { label 'jdk21' }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    stages {
        stage('Verify') {
            steps {
                sh './gradlew --no-daemon clean test'
            }
            post {
                always {
                    junit testResults: 'build/test-results/test/*.xml', allowEmptyResults: true
                }
            }
        }
        stage('Package') {
            when { branch 'develop' }
            steps {
                sh './gradlew --no-daemon bootJar'
                archiveArtifacts artifacts: 'build/libs/*.jar', fingerprint: true
            }
        }
    }
}
