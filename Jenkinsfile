pipeline {
  agent { label 'local-arm64' }
  options {
    disableConcurrentBuilds()
    skipDefaultCheckout(true)
    timeout(time: 90, unit: 'MINUTES')
    buildDiscarder(logRotator(numToKeepStr: '15'))
  }
  parameters {
    string(name: 'DEPLOY_BRANCH', defaultValue: 'main', description: 'Only this application branch may publish')
    string(name: 'REGISTRY', defaultValue: 'hyperscale-k8s-worker:30500', description: 'Registry host, without scheme')
    string(name: 'IMAGE_PREFIX', defaultValue: 'hyperscale-k8s-worker:30500/hyperscale', description: 'Registry host/owner')
    string(name: 'GITOPS_URL', defaultValue: 'git@github.com:nghiango/hyper-scale-commerce-gitops.git', description: 'Canonical SSH GitOps repository URL')
    string(name: 'GITOPS_BRANCH', defaultValue: 'main')
    booleanParam(name: 'RUN_INTEGRATION_TESTS', defaultValue: false, description: 'Run integration tests (Testcontainers)')
  }
  triggers { pollSCM('H/2 * * * *') }
  stages {
    stage('Checkout') {
      steps {
        checkout scm
        script {
          env.REVISION = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
          env.SOURCE_BRANCH = sh(script: 'git branch --show-current', returnStdout: true).trim()
        }
        sh 'bash local-ci/scripts/validate.sh'
      }
    }
    stage('Unit tests') {
      steps { sh './gradlew clean test --no-daemon' }
      post { always { junit allowEmptyResults: false, testResults: '**/build/test-results/test/TEST-*.xml' } }
    }
    stage('Integration tests') {
      when { expression { params.RUN_INTEGRATION_TESTS } }
      steps { sh './gradlew integrationTest --no-daemon' }
      post { always { junit allowEmptyResults: true, testResults: '**/build/test-results/integrationTest/TEST-*.xml' } }
    }
    stage('Build project') {
      steps { sh './gradlew assemble --no-daemon' }
    }
    stage('Build ARM64 images') {
      steps {
        sh 'PUSH_IMAGES=false bash local-ci/scripts/publish.sh'
      }
    }
    stage('Publish ARM64 images') {
      when { expression { (env.BRANCH_NAME ?: env.GIT_LOCAL_BRANCH ?: env.SOURCE_BRANCH) == params.DEPLOY_BRANCH } }
      steps {
        sh 'PUSH_IMAGES=true bash local-ci/scripts/publish.sh'
      }
    }
    stage('Commit GitOps release') {
      when { expression { (env.BRANCH_NAME ?: env.GIT_LOCAL_BRANCH ?: env.SOURCE_BRANCH) == params.DEPLOY_BRANCH } }
      steps {
        sshagent(credentials: ['gitops-write']) {
          sh 'bash local-ci/scripts/gitops.sh'
        }
      }
    }
  }
}
