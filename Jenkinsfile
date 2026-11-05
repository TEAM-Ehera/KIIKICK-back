pipeline {
    agent { label 'jdk21' }

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '20'))
    }

    triggers {
        pollSCM('H/2 * * * *')
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
            when {
                anyOf {
                    branch 'develop'
                    expression { env.JOB_NAME == 'kiikick-develop' && env.GIT_BRANCH == 'origin/develop' }
                }
            }
            steps {
                sh './gradlew --no-daemon bootJar'
                archiveArtifacts artifacts: 'build/libs/*.jar', fingerprint: true
            }
        }
        stage('Deploy development') {
            when {
                expression { env.JOB_NAME == 'kiikick-develop' && env.GIT_BRANCH == 'origin/develop' }
            }
            steps {
                sh '''
                    set -eu
                    jar=build/libs/KIIKICK-back-0.0.1-SNAPSHOT.jar
                    digest=$(sha256sum "$jar" | cut -d ' ' -f 1)
                    sudo -n /usr/local/sbin/kiikick-install-dev-jar "$GIT_COMMIT" "$digest" < "$jar"
                '''
            }
        }
    }
}
