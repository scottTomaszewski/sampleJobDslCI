def modules = [
    [name: 'disclosures', repo: 'ba'],
    [name: 'master-servicing', repo: 'ba'],
    [name: 'bond-admin', repo: 'ba'],
    [name: 'interfaces', repo: 'ba'],
    [name: 'calc-service', repo: 'ba'],
    [name: 'issuance', repo: 'ba'],
    [name: 'tax-service', repo: 'ba'],
    [name: 'data-mart', repo: 'ba'],
    [name: 'io-util', repo: 'ba'],
]

modules.each { Map module ->
  def basePath = module.name
  def repo = "scottTomaszewski/$module.repo"


  folder(basePath) {
      description "Jobs associated with the $module.name module"
  }

  buildPipelineView("$basePath/pipeline") {
    filterBuildQueue()
    filterExecutors()
    title("$basePath CI Pipeline")
    displayedBuilds(5)
    selectedJob("$basePath/build")
    alwaysAllowManualTrigger()
    showPipelineParameters()
    refreshFrequency(60)
  }

  job("$basePath/promote-to-release") {
      parameters {
          stringParam 'host'
      }
      steps {
          shell "echo 'releasing!'"
      }
  }

  job("$basePath/promote-to-staging") {
      scm {
          github repo
      }

      wrappers {
        configFiles {
            mavenSettings('MySettings') {
                variable('SETTINGS_CONFIG')
            }
        }
      }

      steps {
          def script = '''
              CDM_VAR=`mvn help:evaluate -Dexpression=cdm-version|grep -Ev \'(^\\[|Download\\w+:)\'`
              PROJECT_VERSION_VAR=`mvn help:evaluate -Dexpression=project.version|grep -Ev \'(^\\[|Download\\w+:)\'`
              SEMVER="[^0-9]*\\([0-9]*\\)[.]\\([0-9]*\\)[.]\\([0-9]*\\)\\([0-9A-Za-z-]*\\)"
              WITHOUT_SNAPSHOT=${PROJECT_VERSION_VAR%-SNAPSHOT}
              RELEASE_VER_VAR=`echo $WITHOUT_SNAPSHOT | sed -e "s#$SEMVER#\\1.\\2.${BUILD_NUMBER}\\4#"`
              echo "CDM=$CDM_VAR PROJECT_VERSION=$PROJECT_VERSION_VAR"
              echo "CDM=$CDM_VAR" >> env.properties
              echo "PROJECT_VERSION=$PROJECT_VERSION_VAR" >> env.properties
              echo "RELEASE_VERSION=$RELEASE_VER_VAR" >> env.properties

          '''
          shell script
          environmentVariables {
            propertiesFile('env.properties')
          }
          buildDescription(/^(CDM=.*)\sPROJECT_VERSION=(.*)/, '\\2 (\\1)')
          wrappers {
              buildName('#${BUILD_NUMBER} - ${GIT_REVISION, length=8} (${GIT_BRANCH})')
          }
          maven("versions:set -DnewVersion=\'\${RELEASE_VERSION}-$basePath\'")
          maven('clean install deploy -s ${SETTINGS_CONFIG} -DdeployAtEnd')
          maven("versions:set -DnewVersion=\'\${RELEASE_VERSION}\'")
      }
  }

  job("$basePath/integration-tests") {
      scm {
          github repo
      }
      steps {
          shell "echo 'Running integration tests.  Yay.'"
          buildDescription(/^(CDM=.*)\sPROJECT_VERSION=(.*)/, '\\2 (\\1)')
          wrappers {
              buildName('#${BUILD_NUMBER} - ${GIT_REVISION, length=8} (${GIT_BRANCH})')
          }
      }
      publishers {
          downstream("$basePath/promote-to-staging", 'SUCCESS')
      }
  }

  job("$basePath/build") {
      scm {
          github repo
      }
      triggers {
          scm 'H/2 * * * *'
      }
      steps {
          maven('clean install')
          def script = '''
              CDM_VAR=`mvn help:evaluate -Dexpression=cdm-version|grep -Ev \'(^\\[|Download\\w+:)\'`
              PROJECT_VERSION_VAR=`mvn help:evaluate -Dexpression=project.version|grep -Ev \'(^\\[|Download\\w+:)\'`
              echo "CDM=$CDM_VAR PROJECT_VERSION=$PROJECT_VERSION_VAR"
          '''
          shell script
          buildDescription(/^(CDM=.*)\sPROJECT_VERSION=(.*)/, '\\2 (\\1)')
          wrappers {
              buildName('#${BUILD_NUMBER} - ${GIT_REVISION, length=8} (${GIT_BRANCH})')
          }
      }
      publishers {
          downstream("$basePath/integration-tests", 'SUCCESS')
      }

  }
}
