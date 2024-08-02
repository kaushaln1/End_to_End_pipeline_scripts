### Jenkins Pipeline for Spring Boot Application

This document provides a detailed overview of the Jenkins pipeline for building, testing, and deploying a Spring Boot application. Additionally, it lists the prerequisites required on the Jenkins server and Kubernetes cluster to ensure a successful deployment.

#### Prerequisites

Before running the Jenkins pipeline, ensure the following components are installed and configured on your Jenkins server and Kubernetes cluster:

#### Jenkins Server Setup

1. **Java Development Kit (JDK 17)**:
   - Install JDK 17 and configure it in Jenkins under `Manage Jenkins > Global Tool Configuration > JDK`.

2. **Maven**:
   - Install Maven and configure it in Jenkins under `Manage Jenkins > Global Tool Configuration > Maven`.

3. **Trivy**:
   - Install Trivy for scanning Docker images:
     ```sh
     wget https://github.com/aquasecurity/trivy/releases/download/v0.33.0/trivy_0.33.0_Linux-64bit.deb
     sudo dpkg -i trivy_0.33.0_Linux-64bit.deb
     ```

4. **OWASP Dependency-Check**:
   - Install OWASP Dependency-Check plugin:
     - Go to `Manage Jenkins > Manage Plugins > Available` and search for `OWASP Dependency-Check Plugin`.
     - Install the plugin.

5. **SonarQube**:
   - Install SonarQube Scanner:
     - Go to `Manage Jenkins > Global Tool Configuration > SonarQube Scanner`.
     - Add SonarQube Scanner and configure its installation path.

6. **Docker**:
   - Ensure Docker is installed and running on the Jenkins server.
     ```sh
     sudo apt-get update
     sudo apt-get install docker.io
     sudo usermod -aG docker jenkins
     ```

7. **Helm**:
   - Install Helm:
     ```sh
     curl https://baltocdn.com/helm/signing.asc | sudo apt-key add -
     sudo apt-get install apt-transport-https --yes
     echo "deb https://baltocdn.com/helm/stable/debian/ all main" | sudo tee /etc/apt/sources.list.d/helm-stable-debian.list
     sudo apt-get update
     sudo apt-get install helm
     ```

#### Kubernetes Cluster Setup

1. **Helm**:
   - Helm must be installed on the cluster to manage Kubernetes applications using Helm charts. [Helm Installation Guide](https://helm.sh/docs/intro/install/)

2. **Namespace**:
   - Create a namespace `jenkins-agent` where the application will be deployed.
     ```sh
     kubectl create namespace jenkins-agent
     ```

3. **Kubeconfig**:
   - Ensure that the `kubeconfig` file is securely stored in Jenkins credentials and accessible during the pipeline execution.

#### Jenkins Pipeline Script

```groovy
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
                    // Checkout code from GitHub
                    checkout([$class: 'GitSCM', 
                        branches: [[name: 'main']], 
                        userRemoteConfigs: [[url: 'https://github.com/kaushaln1/SpringBootApp.git']]
                    ])

                    // Extract appName and version from pom.xml or package.json
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
}
```

This setup ensures that your Spring Boot application is automatically built, tested, scanned, and deployed to a Kubernetes cluster using Helm, with all necessary stages handled efficiently in the Jenkins pipeline.
