pipeline {
    agent any
    tools {
        jdk 'jdk17'
        maven 'maven3'
    }
    environment {
        KUBECONFIG = 'kubeconfig'
        APP_NAME = ''
        VERSION = ''
    }
  
    stages {
        stage('Checkout') {
            steps {
                script {
                   
                    checkout([$class: 'GitSCM', 
                        branches: [[name: 'main']], 
                        userRemoteConfigs: [[url: 'https://github.com/kaushaln1/SpringBootApp.git']]
                    ])

                    
                    def appName = sh(script: '''
                        grep -oPm1 "(?<=<name>)[^<]+" pom.xml || grep -oPm1 "(?<=\"name\": \")[^\"]+" package.json
                    ''', returnStdout: true).trim().toLowerCase().replaceAll("\\s", "")

                    def version = sh(script: '''
                        grep -oPm1 "(?<=<version>)[^<]+" pom.xml || grep -oPm1 "(?<=\"version\": \")[^\"]+" package.json
                    ''', returnStdout: true).trim()

                    appName = appName.toLowerCase().replaceAll("\\s", "")
                    
                    env.APP_NAME = appName
                    env.VERSION = version
                }
            }
        }

        stage('Compile') {
            steps {
                sh "mvn clean compile"
            }
        }

        stage('Run tests') {
            steps {
                sh "mvn test"
            }
        }

        stage('Sonar Scan') {
            environment {
                scannerHome = tool 'SonarQubeScanner'
            }
            steps {
                withSonarQubeEnv(credentialsId: 'SonarQube', installationName: 'sonarCubeServer') {
                    sh '''${scannerHome}/bin/sonar-scanner -Dsonar.projectName=${env.APP_NAME} \
                          -Dsonar.java.binaries=. \
                          -Dsonar.projectKey=${env.APP_NAME}'''
                }
            }
        }

        stage('OWASP Check') {
            steps {
                dependencyCheck additionalArguments: ' --scan ./', odcInstallation: 'Check-DP'
                dependencyCheckPublisher pattern: '**/dependency-check-report.xml'
            }
        }

        stage('Build') {
            steps {
                sh "mvn clean install"
            }
        }

        stage('Docker Build') {
            steps {
                script {
                    sh "docker build -t ${env.APP_NAME}:${env.VERSION} ."
                    sh "docker tag ${env.APP_NAME}:${env.VERSION} kauhsaln1/${env.APP_NAME}:latest"
                }
            }
        }

        stage('Scan Docker Image') {
            steps {
                script {
                    def formatOption = "--format template --template \"@/usr/local/share/trivy/templates/html.tpl\""
                    def imageFullName = "kauhsaln1/${env.APP_NAME}:latest"

                    sh """
                    trivy image $imageFullName $formatOption --timeout 10m --output report.html || true
                    """
                }
                publishHTML(target: [
                    allowMissing: true,
                    alwaysLinkToLastBuild: false,
                    keepAll: true,
                    reportDir: ".",
                    reportFiles: "report.html",
                    reportName: "Trivy Report",
                ])
            }
        }

        stage('Push Docker Image') {
            steps {
                withCredentials([usernamePassword(credentialsId: 'dockerCreds', passwordVariable: 'dockerPass', usernameVariable: 'dockerUser')]) {
                    sh 'docker login -u ${dockerUser} --password ${dockerPass}'
                    sh "docker push kauhsaln1/${env.APP_NAME}:latest"
                }
            }
        }

        stage('Deploy to Kubernetes-Helm') {
            steps {
                script {
                    
                    sh 'helm repo add kaushaln1 https://kaushaln1.github.io/helm_charts'
                    sh 'helm repo update'
                    sh "helm install ${env.APP_NAME} kaushaln1/springboot --namespace jenkins-agent -f dev/values.yaml"
                }
            }
        }
    }

    post {
        always {
            cleanWs()
        }
    }
}
