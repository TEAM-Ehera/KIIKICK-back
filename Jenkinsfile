def notifyDeploy(String state) {
    catchError(buildResult: 'SUCCESS', stageResult: 'SUCCESS', catchInterruptions: false,
               message: "Discord deployment notification failed (${state})") {
        withCredentials([string(credentialsId: 'kiikick-deploy-discord-webhook',
                                variable: 'DISCORD_WEBHOOK_URL')]) {
            withEnv(["KIIKICK_DEPLOY_STATE=${state}"]) {
                sh 'set +x; python3 -B ops/deploy/notify-discord.py'
            }
        }
    }
}

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
        stage('Deploy development container') {
            when {
                expression { env.JOB_NAME == 'kiikick-develop' && env.GIT_BRANCH == 'origin/develop' }
            }
            steps {
                script {
                    notifyDeploy('started')
                    def deployed = false
                    try {
                        sh '''
                            set -eu
                            jar=build/libs/KIIKICK-back-0.0.1-SNAPSHOT.jar
                            digest=$(sha256sum "$jar" | cut -d ' ' -f 1)
                            sudo -n /usr/local/sbin/kiikick-install-dev-container "$GIT_COMMIT" "$digest" < "$jar"
                        '''
                        deployed = true
                    } finally {
                        notifyDeploy(deployed ? 'succeeded' : 'failed')
                    }
                }
            }
        }
    }
}
