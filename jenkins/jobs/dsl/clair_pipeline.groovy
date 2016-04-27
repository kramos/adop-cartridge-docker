// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"
def projectFolderName = "${PROJECT_NAME}"

// Variables
def dockerUtilsRepo = "adop-cartridge-docker-scripts"
def dockerUtilsGitUrl = "ssh://jenkins@gerrit:29418/${PROJECT_NAME}/" + dockerUtilsRepo 

// Jobs
def createClairInstance = freeStyleJob(projectFolderName + "/Create_Clair_Instance")
def removeClairInstance = freeStyleJob(projectFolderName + "/Remove_Clair_Instance")

// Views
def pipelineView = buildPipelineView(projectFolderName + "/Create_Clair_Pipeline")

pipelineView.with{
    title('Create Clair Pipeline')
    displayedBuilds(5)
    selectedJob(projectFolderName + "/Create_Clair_Instance")
    showPipelineParameters()
    showPipelineDefinitionHeader()
    refreshFrequency(5)
}

createClairInstance.with{
  description("This job creates a local Clair instance).")
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
            |cd vulnerabilityScan/coreos_clair
            |export CUSTOM_NETWORK_NAME=local_network
            |docker-compose -f ./docker-compose.yml up -d
            |'''.stripMargin())
  }
  publishers{
    archiveArtifacts("**/*")
    buildPipelineTrigger("${PROJECT_NAME}/Remove_Clair_Instance") {
            parameters {
                currentBuild()
            }
    }
  }
}

removeClairInstance.with{
  description("This job removes the Clair instance")
  parameters{
    stringParam("B",'',"Parent build number")
    stringParam("PARENT_BUILD","Create_Clair_Instance","Parent build name")
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
    copyArtifacts('Create_Clair_Instance') {
        buildSelector {
          buildNumber('${B}')
      }
    }
    shell('''set -xe
            |cd adop-cartridge-docker-scripts/vulnerabilityScan/coreos_clairdd
            |export CUSTOM_NETWORK_NAME=
            |docker-compose -f ./docker-compose.yml rm -f
            |'''.stripMargin())
  }
}

