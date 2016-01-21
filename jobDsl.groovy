def modules = [
        [name: 'disclosures', repo: 'ba'],
        //[name: 'master-servicing', repo: 'ba'],
        //[name: 'bond-admin', repo: 'ba'],
        //[name: 'interfaces', repo: 'ba'],
        //[name: 'calc-service', repo: 'ba'],
        //[name: 'issuance', repo: 'ba'],
        //[name: 'tax-service', repo: 'ba'],
        //[name: 'data-mart', repo: 'ba'],
        //[name: 'io-util', repo: 'ba'],
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
        refreshFrequency(5)
    }

    // Job names
    def buildAndReleaseToStaging = "$basePath/release-to-staging"
    def integrationTests = "$basePath/integration-tests"
    def promoteToRelease = "$basePath/promote-to-release"

    job(buildAndReleaseToStaging) {
        description("Job for testing $basePath then releasing successful builds to the staging artifact repository")

        scm {
            github repo
        }

        triggers {
            scm 'H/2 * * * *'
        }

        wrappers {
            // clean workspace
            preBuildCleanup()

            // Add maven settings.xml from Managed Config Files
            configFiles {
                mavenSettings('MySettings') {
                    variable('SETTINGS_CONFIG')
                }
            }
        }

        steps {
            def script = '''
                # prepare git
                git config user.name "Jenkins"
                git config user.email "DevOps_Team@FIXME.com"
                git config push.default simple

                # figure out git commit count
                GIT_COMMIT_COUNT=`git rev-list --all --count`

                # evaluate cdm version from property
                CDM_VAR=`mvn help:evaluate -Dexpression=cdm-version|grep -Ev \'(^\\[|Download\\w+:)\'`

                # evaluate declared project version from GAV
                PROJECT_VERSION_VAR=`mvn help:evaluate -Dexpression=project.version|grep -Ev \'(^\\[|Download\\w+:)\'`

                # remove "-SNAPSHOT" from project version
                WITHOUT_SNAPSHOT=${PROJECT_VERSION_VAR%-SNAPSHOT}

                # release version as MAJOR.MINOR.BUILD_NUMBER.SUFFIX
                SEMVER="[^0-9]*\\([0-9]*\\)[.]\\([0-9]*\\)[.]\\([0-9]*\\)\\([0-9A-Za-z-]*\\)"
                RELEASE_VER_VAR=`echo $WITHOUT_SNAPSHOT | sed -e "s#$SEMVER#\\1.\\2.${GIT_COMMIT_COUNT}\\4#"`

                # next version as MAJOR.MINOR.[BUILD_NUMBER+1].SUFFIX
                NEXT_VER_VAR=`echo $PROJECT_VERSION_VAR | sed -e "s#$SEMVER#\\1.\\2.$((GIT_COMMIT_COUNT+1))\\4#"`

                # create a branch for safekeeping
                git checkout -b staging-v$RELEASE_VER_VAR

                # Add properties for EnvInject jenkins plugin
                echo "CDM=$CDM_VAR" >> env.properties
                echo "PROJECT_VERSION=$PROJECT_VERSION_VAR" >> env.properties
                echo "RELEASE_VERSION=$RELEASE_VER_VAR" >> env.properties
                echo "NEXT_VERSION=$NEXT_VER_VAR" >> env.properties

                # print out description for Description Setter jenkins plugin
                echo "DESCRIPTION v$RELEASE_VER_VAR (CDM=$CDM_VAR)"
            '''
            shell script

            environmentVariables {
                propertiesFile('env.properties')
            }
            buildDescription(/^DESCRIPTION\s(.*)/, '\\1')
            wrappers {
                buildName('#${BUILD_NUMBER} - ${GIT_REVISION, length=8} (${GIT_BRANCH})')
            }

            // set release version on poms (temp: add basePath since using same git repo) and commit
            maven("versions:set -DnewVersion=\'\${RELEASE_VERSION}-$basePath\'")
            shell "git commit -am '[promote-to-staging] Bumping version to staging -> \${RELEASE_VERSION}'"

            // test and deploy to nexus, then tag
            maven('clean install deploy -s ${SETTINGS_CONFIG} -DdeployAtEnd')
            shell "git tag staging-\${RELEASE_VERSION} # TODO && git push --tags"

            // switch to original branch
            shell "git checkout -"

            // increment and update to new version
            maven("versions:set -DnewVersion=\'\${NEXT_VERSION}\'")
            shell "git commit -am '[promote-to-staging] Bumping after staging \${RELEASE_VERSION}. New version: \${NEXT_VERSION}' # TODO && git push"
        }

        publishers {
            downstreamParameterized {
                trigger(integrationTests) {
                    condition('SUCCESS')
                    parameters {
                        predefinedProp("ARTIFACT_BUILD_NUMBER", "\${BUILD_NUMBER}")
                        predefinedProp("RELEASE_VERSION", "\${RELEASE_VERSION}-$basePath")
                    }
                }
            }
        }
    }

    job(integrationTests) {
        description("Job for running integration tests for $basePath")

        scm {
            github repo
        }

        wrappers {
            // clean workspace
            preBuildCleanup()
        }

        steps {
            shell "echo 'Running integration tests.  Yay.'"
            buildDescription(/^(CDM=.*)\sPROJECT_VERSION=(.*)/, '\\2 (\\1)')
            wrappers {
                buildName('#${BUILD_NUMBER} - ${GIT_REVISION, length=8} (${GIT_BRANCH})')
            }
        }
    }

    job(promoteToRelease) {
        description("Job for promoting successful $basePath releases from the staging artifact repository to the public releases artifact repository")

        wrappers {
            // clean workspace
            preBuildCleanup()
        }

        steps {
            shell "echo 'releasing!'"
        }
    }
}
