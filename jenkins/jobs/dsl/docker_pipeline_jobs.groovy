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
def vunerabilityScan = freeStyleJob(projectFolderName + "/Vulnerability_Scan")
def imageTest = freeStyleJob(projectFolderName + "/Image_Test")
def containerTest = freeStyleJob(projectFolderName + "/Container_Test")
def publish = freeStyleJob(projectFolderName + "/Publish")
def integrationTestTestingImage = freeStyleJob(projectFolderName + "/Integration_Test_With_Testing_Image")
def integrationTestImage = freeStyleJob(projectFolderName + "/Integration_Test_Image")
def tagAndRelease = freeStyleJob(projectFolderName + "/Tag_and_Release")

// Views
def pipelineView = buildPipelineView(projectFolderName + "/Chef_Pipeline")

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
            |echo Pull the Dockerfile out of Git ready for us to test and if successful release via the pipeline
            |'''.stripMargin())
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
            |echo Mount the Dockerfile into a container that will run Docker Lint https://github.com/projectatomic/dockerfile_lint
            |docker run --rm -v jenkins_slave_home:/jenkins_slave_home/ redcoolbeans/dockerlint dockerlint -f /jenkins_slave_home/$JOB_NAME/Static_Code_Analysis/Dockerfile
            |'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    downstreamParameterized{
      trigger(projectFolderName + "/Build"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${BUILD_NUMBER}')
          predefinedProp("PARENT_BUILD", '${JOB_NAME}')
        }
      }
    }
  }
}


build.with{
  description("This job build the Docker image")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Static_Code_Analysis","Parent build name")
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
            |docker build -t '''.stripMargin() + 
	     referenceAppGitRepo + 
             '''|'''.stripMargin())
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Vulnerability_Scan"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
        }
      }
    }
  }
}

vunerabilityScan.with{
  description("This will build a wrapper testing container FROM the image under test adding all testing dependencies (that we wouldn't want in production) then run the container to test it's self.")
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
    shell('''set +x
            |echo This will build a wrapper testing container FROM the image under test adding all testing dependencies (that we wouldn't want in production) then run the container to test it's self.
            |'''.stripMargin())
    copyArtifacts("Get_Dockerfile") {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set +x
            |echo TODO run kitchen
            |'''.stripMargin())
  }
  publishers{
    downstreamParameterized{
      trigger(projectFolderName + "/Image_Test"){
        condition("UNSTABLE_OR_BETTER")
        parameters{
          predefinedProp("B",'${B}')
          predefinedProp("PARENT_BUILD", '${PARENT_BUILD}')
        }
      }
    }
  }
}

imageTest.with{
  description("This job uploads the cookbook to the non-production Chef Server")
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
    shell('''set +x
            |echo TODO converge 
            |set -x'''.stripMargin())
  }

}

