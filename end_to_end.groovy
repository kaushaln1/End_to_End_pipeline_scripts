pipeline {
    agent any
    tools {
        jdk 'jdk17'
        maven 'maven3'
    }
    environment {
        KUBECONFIG = 'kubeconfig'
    }
  
    stages {
        stage('Checkout') {
            steps {
                script {
                
                    checkout([$class: 'GitSCM', 
                        branches: [[name: 'main']], 
                        userRemoteConfigs: [[url: 'https://github.com/kaushaln1/SpringBootApp.git']]
                    ])

                    def appName = ''
                    def version = ''
                    
                    if (fileExists('pom.xml')) {
                        appName = sh(script: "mvn help:evaluate -Dexpression=project.name -q -DforceStdout", returnStdout: true).trim()
                        version = sh(script: "mvn help:evaluate -Dexpression=project.version -q -DforceStdout", returnStdout: true).trim()
                    } else if (fileExists('package.json')) {
                        appName = sh(script: "jq -r .name package.json", returnStdout: true).trim()
                        version = sh(script: "jq -r .version package.json", returnStdout: true).trim()
                    }
                    
                    // Print extracted values for debugging
                    echo "Extracted appName: ${appName}"
                    echo "Extracted version: ${version}"
                    
                    // Set environment variables
                    env.APP_NAME = appName.toLowerCase()
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
                sonarProjectName = "${env.APP_NAME}"
            }
            steps {
                withSonarQubeEnv(credentialsId: 'SonarQube', installationName: 'sonarCubeServer') {
                    sh '''${scannerHome}/bin/sonar-scanner -Dsonar.projectName=${sonarProjectName} \
                          -Dsonar.java.binaries=. \
                          -Dsonar.projectKey=${sonarProjectName}'''
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
                    sh "docker tag ${env.APP_NAME}:${env.VERSION} kaushaln1/${env.APP_NAME}:latest"
                }
            }
        }

        stage('Scan Docker Image') {
            steps {
                script {
                    def formatOption = "--format template --template \"@/usr/local/share/trivy/templates/html.tpl\""
                    def imageFullName = "kaushaln1/${env.APP_NAME}:latest"

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
                    sh "docker push kaushaln1/${env.APP_NAME}:latest"
                }
            }
        }

        stage('Deploy to Kubernetes-Helm') {
            steps {
                script {
             
                    
                    sh 'helm repo add kaushaln1 https://kaushaln1.github.io/helm_charts'
                    sh 'helm repo update'
                    // sh "helm install ${env.APP_NAME} kaushaln1/springboot --namespace jenkins-agent -f dev/values.yaml"
                    
                    withCredentials([file(credentialsId: 'kubeconfig', variable: 'config')]) {
                          sh """
                          export KUBECONFIG=\${config}
                          helm install ${env.APP_NAME} kaushaln1/springboot --namespace jenkins-agent -f dev/values.yaml
                          """
        }
                }
            }
        }

}
