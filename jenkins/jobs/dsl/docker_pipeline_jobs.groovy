// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables
def referenceAppGitRepo = "adop-jenkins"
def referenceAppGitUrl = "ssh://jenkins@gerrit:29418/${PROJECT_NAME}/" + referenceAppGitRepo
def dockerUtilsRepo = "adop-cartridge-docker-scripts"
def dockerUtilsGitUrl = "ssh://jenkins@gerrit:29418/${PROJECT_NAME}/" + dockerUtilsRepo 

// Jobs
def getDockerfile = freeStyleJob(projectFolderName + "/Get_Dockerfile")
def staticCodeAnalysis = freeStyleJob(projectFolderName + "/Static_Code_Analysis")
def build = freeStyleJob(projectFolderName + "/Build")
def vulnerabilityScan = freeStyleJob(projectFolderName + "/Vulnerability_Scan")
def imageTest = freeStyleJob(projectFolderName + "/Image_Test")
def containerTest = freeStyleJob(projectFolderName + "/Container_Test")
def publish = freeStyleJob(projectFolderName + "/Publish")
def integrationTestTestingImage = freeStyleJob(projectFolderName + "/Integration_Test_With_Testing_Image")
def integrationTestImage = freeStyleJob(projectFolderName + "/Integration_Test_Image")
def tagAndRelease = freeStyleJob(projectFolderName + "/Tag_and_Release")

// Views
def pipelineView = buildPipelineView(projectFolderName + "/Example_Docker_Pipeline")

pipelineView.with{
    title('Example Docker Pipeline')
    displayedBuilds(5)
    selectedJob(projectFolderName + "/Get_Dockerfile")
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(5)
}

getDockerfile.with{
  description("This job downloads the Dockerfile (and local resources).")
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  scm{
    git{
      remote{
        url(referenceAppGitUrl)
        credentials("adop-jenkins-master")
      }
      branch("*/master")
    }
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  triggers{
    gerrit{
      events{
        refUpdated()
      }
      configure { gerritxml ->
        gerritxml / 'gerritProjects' {
          'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.GerritProject' {
            compareType("PLAIN")
            pattern(projectFolderName + "/" + referenceAppgitRepo)
            'branches' {
              'com.sonyericsson.hudson.plugins.gerrit.trigger.hudsontrigger.data.Branch' {
                compareType("PLAIN")
                pattern("master")
              }
            }
          }
        }
        gerritxml / serverName("ADOP Gerrit")
      }
    }
  }
  label("docker")
  steps {
    shell('''set -xe
            |echo "Pull the Dockerfile out of Git ready for us to test and if successful release via the pipeline"
            |set +xe'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Static_Code_Analysis"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${BUILD_NUMBER}')
          predefinedProp("PARENT_BUILD",'${JOB_NAME}')
        }
      }
    }
  }
}

staticCodeAnalysis.with{
  description("This job performs static code analysis on the Dockerfile.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  label("docker")
  steps {
    copyArtifacts('Get_Dockerfile') {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo "Mount the Dockerfile into a container that will run Dockerlint https://github.com/projectatomic/dockerfile_lint"
            |docker run --rm -v jenkins_slave_home:/jenkins_slave_home/ --entrypoint="dockerlint" redcoolbeans/dockerlint -f /jenkins_slave_home/$JOB_NAME/Dockerfile > ${WORKSPACE}/staticCodeAnalysis.out
            |#if ! grep "Dockerfile is OK" ${WORKSPACE}/staticCodeAnalysis.out; then
            |# exit 1
            |#fi
            |set +x'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Build"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${JOB_NAME}')
        }
      }
    }
  }
}

build.with{
  description("This job builds the Docker image.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  label("docker")
  steps {
    copyArtifacts('Get_Dockerfile') {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo "Build the docker container image locally"
            |docker build -t '''.stripMargin() + referenceAppGitRepo + ''' .
            |set +x'''.stripMargin())
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Vulnerability_Scan"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${JOB_NAME}')
        }
      }
    }
  }
}

vulnerabilityScan.with{
  description("This job tests the image against a database of known vulnerabilities using Clair, an open source static analysis tool https://github.com/coreos/clair.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_Dockerfile") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo "Use the darrenajackson/analyze-local-images container to analyse the image"
            |docker run --net=host --rm -v /tmp:/tmp -v /var/run/docker.sock:/var/run/docker.sock darrenajackson/analyze-local-images '''.stripMargin() + referenceAppGitRepo + ''' > ${WORKSPACE}/vulnerabilityScan.out
            |#if ! grep "^Success! No vulnerabilities were detected in your image$" ${WORKSPACE}/vulnerabilityScan.out; then
            |# exit 1
            |#fi
            |set +x'''.stripMargin())
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Image_Test"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${JOB_NAME}')
        }
      }
    }
  }
}

imageTest.with{
  description("This job uses a python script to analyse the output from docker inspect against a configuration file that details required parameters. It also looks for any unexpected additions to the new image being tested.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  scm{
    git{
      remote{
        url(dockerUtilsGitUrl)
        credentials("adop-jenkins-master")
      }
      branch("*/master")
    }
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_Dockerfile") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo "Use the darrenajackson/image-inspector container to inspect the image"
            |export TESTS_PATH="adop-jenkins/tests/image-test"
            |export TEST_DIR="/tmp"
            |export docker_workspace_dir=$(echo ${WORKSPACE} | sed 's#/workspace#/var/lib/docker/volumes/jenkins_slave_home/_data#')
            |docker run --net=host --rm -v ${docker_workspace_dir}/${TESTS_PATH}/:${TEST_DIR} -v /var/run/docker.sock:/var/run/docker.sock darrenajackson/image-inspector -i '''.stripMargin() + referenceAppGitRepo + ''' -f ${TEST_DIR}/'''.stripMargin() + referenceAppGitRepo + '''.cfg > ${WORKSPACE}/imageTest.out
            |#if grep "ERROR" ${WORKSPACE}/imageTest.out; then
            |# exit 1
            |#fi
            |set +x'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Container_Test"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${BUILD_NUMBER}')
          predefinedProp("PARENT_BUILD",'${JOB_NAME}')
        }
      }
    }
  }
}

containerTest.with{
  description("This job creates a new testing image from the image being tested that also contains all the tools necessary for internal testing of the image.")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  scm{
    git{
      remote{
        url(dockerUtilsGitUrl)
        credentials("adop-jenkins-master")
      }
      branch("*/master")
    }
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_Dockerfile") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo "Build a new test image installing required applications, run test suite and destroy the new image and container at the end of the tests"
            |export TESTS_PATH="adop-jenkins/tests/container-test"
            |export CT_PATH="containerTest/test-image"
            |export IMG_TAG="test"
            |export TEST_DIR="/var/tmp"
            |export docker_workspace_dir=$(echo ${WORKSPACE} | sed 's#/workspace#/var/lib/docker/volumes/jenkins_slave_home/_data#')
            |source ${WORKSPACE}/${TESTS_PATH}/dockerfile_envs.sh
            |sed -e 's#<@FROM-IMAGE@>#'''.stripMargin() + referenceAppGitRepo + '''#' -e "s#<@PACK-LIST@>#${PACK_LIST}#" ${WORKSPACE}/${CT_PATH}/Dockerfile.template > ${WORKSPACE}/Dockerfile
            |cat ${WORKSPACE}/${TESTS_PATH}/envs.cfg >> ${WORKSPACE}/Dockerfile
            |docker build -t '''.stripMargin() + referenceAppGitRepo + '''-${IMG_TAG}:${IMG_TAG} ${WORKSPACE}
            |docker run -d --name '''.stripMargin() + referenceAppGitRepo + '''-${IMG_TAG} -v ${docker_workspace_dir}/${TESTS_PATH}/:${TEST_DIR} '''.stripMargin() + referenceAppGitRepo + '''-${IMG_TAG}:${IMG_TAG}
            |sleep 60
            |docker exec '''.stripMargin() + referenceAppGitRepo + '''-${IMG_TAG} ${TEST_DIR}/container_tests.sh > ${WORKSPACE}/containerTest.out
            |docker stop '''.stripMargin() + referenceAppGitRepo + '''-${IMG_TAG} && docker rm -v '''.stripMargin() + referenceAppGitRepo + '''-${IMG_TAG} && docker rmi '''.stripMargin() + referenceAppGitRepo + '''-${IMG_TAG}:${IMG_TAG}
            |#if grep "^-" ${WORKSPACE}/containerTest.out; then
            |# exit 1
            |#fi
            |set -x'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Publish"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${BUILD_NUMBER}')
          predefinedProp("PARENT_BUILD",'${JOB_NAME}')
        }
      }
    }
  }
}

publish.with{
  description("This job TODO")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_Dockerfile") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo TODO converge 
            |set +x'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Integration_Test_With_Testing_Image"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${BUILD_NUMBER}')
          predefinedProp("PARENT_BUILD",'${JOB_NAME}')
        }
      }
    }
  }
}

integrationTestTestingImage.with{
  description("This job TODO")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_Dockerfile") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo TODO converge 
            |set +x'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Integration_Test_Image"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${BUILD_NUMBER}')
          predefinedProp("PARENT_BUILD",'${JOB_NAME}')
        }
      }
    }
  }
}

integrationTestImage.with{
  description("This job TODO")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_Dockerfile") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo TODO converge 
            |set +x'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Tag_and_Release"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${BUILD_NUMBER}')
          predefinedProp("PARENT_BUILD",'${JOB_NAME}')
        }
      }
    }
  }
}

tagAndRelease.with{
  description("This job TODO")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Get_Dockerfile","Parent build name")
  }
  wrappers {
    preBuildCleanup()
    injectPasswords()
    maskPasswords()
    sshAgent("adop-jenkins-master")
  }
  environmentVariables {
      env('WORKSPACE_NAME',workspaceFolderName)
      env('PROJECT_NAME',projectFolderName)
  }
  label("docker")
  steps {
    copyArtifacts("Get_Dockerfile") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -x
            |echo TODO converge 
            |set +x'''.stripMargin())
  }
}
