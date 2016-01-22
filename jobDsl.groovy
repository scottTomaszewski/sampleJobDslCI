def modules = [
        [name: 'ds', repo: 'dis', branches: ['master', 'feature']],
        [name: 'ms', repo: 'ba', branches: ['master']],
        [name: 'ba', repo: 'ba', branches: ['master']],
        //[name: 'if', repo: 'ba', branches: ['master']],
        //[name: 'cs', repo: 'ba', branches: ['master']],
        //[name: 'is', repo: 'ba', branches: ['master']],
        //[name: 'ts', repo: 'ba', branches: ['master']],
        //[name: 'dm', repo: 'ba', branches: ['master']],
        //[name: 'io', repo: 'ba', branches: ['master']],
]

modules.each { Map module ->
    def modulePath = module.name
    def repo = "scottTomaszewski/$module.repo"

    folder(modulePath) {
        description "Jobs associated with the $module.name module"
    }

    module.branches.each { branch ->
        def branchPath = "$modulePath/$branch"

        folder(branchPath) {
            description "Jobs associated with the $module.name module on the '$branch' branch"
        }

        // Job names
        def buildAndReleaseToStaging = "$branchPath/release-to-staging"
        def integrationTests = "$branchPath/integration-tests"
        def promoteToRelease = "$branchPath/promote-to-release"

        buildPipelineView("$branchPath/pipeline") {
            filterBuildQueue()
            filterExecutors()
            title("$branchPath CI Pipeline")
            displayedBuilds(5)
            selectedJob(buildAndReleaseToStaging)
            alwaysAllowManualTrigger()
            showPipelineParameters()
            refreshFrequency(5)
        }

        job(buildAndReleaseToStaging) {
            description("Job for testing $branchPath then releasing successful builds to the staging artifact repository")

            scm {
                github(repo, branch)
            }

            triggers {
                scm '* * * * *'
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

                # evaluate declared project info
                PROJECT_VERSION_VAR=`mvn help:evaluate -Dexpression=project.version|grep -Ev \'(^\\[|Download\\w+:)\'`
                PROJECT_GROUP_ID_VAR=`mvn help:evaluate -Dexpression=project.groupId|grep -Ev '(^\\[|Download\\w+:)'`
                PROJECT_ARTIFACT_ID_VAR=`mvn help:evaluate -Dexpression=project.artifactId|grep -Ev '(^\\[|Download\\w+:)'`

                # remove "-SNAPSHOT" from project version
                WITHOUT_SNAPSHOT=${PROJECT_VERSION_VAR%-SNAPSHOT}

                # release version as MAJOR.MINOR.GIT_COMMIT_COUNT.SUFFIX
                SEMVER="[^0-9]*\\([0-9]*\\)[.]\\([0-9]*\\)[.]\\([0-9]*\\)\\([0-9A-Za-z-]*\\)"
                RELEASE_VER_VAR=`echo $WITHOUT_SNAPSHOT | sed -e "s#$SEMVER#\\1.\\2.${GIT_COMMIT_COUNT}\\4#"`

                # next version as MAJOR.MINOR.[GIT_COMMIT_COUNT+1].SUFFIX
                NEXT_VER_VAR=`echo $PROJECT_VERSION_VAR | sed -e "s#$SEMVER#\\1.\\2.$((GIT_COMMIT_COUNT+1))\\4#"`

                # create a branch for safekeeping
                git checkout -b staging-v$RELEASE_VER_VAR

                # Add properties for EnvInject jenkins plugin
                echo "CDM=$CDM_VAR" >> env.properties
                echo "PROJECT_VERSION=$PROJECT_VERSION_VAR" >> env.properties
                echo "PROJECT_GROUP_ID=$PROJECT_GROUP_ID_VAR" >> env.properties
                echo "PROJECT_ARTIFACT_ID=$PROJECT_ARTIFACT_ID_VAR" >> env.properties
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

                // set release version on poms (temp: add branchPath since using same git repo) and commit
                maven("versions:set -DnewVersion=\'\${RELEASE_VERSION}\'")
                shell 'git commit -am "[promote-to-staging] Bumping version to staging -> \${RELEASE_VERSION}"'

                // test and deploy to nexus, then tag
                maven('clean install deploy -s ${SETTINGS_CONFIG} -DdeployAtEnd')
                shell "git tag staging-\${RELEASE_VERSION} # TODO && git push --tags"

                // switch to original branch
                shell "git checkout -"

                // increment and update to new version
                maven("versions:set -DnewVersion=\'\${NEXT_VERSION}\'")
                shell 'git commit -am "[promote-to-staging] Bumping after staging \${RELEASE_VERSION}. New version: \${NEXT_VERSION}" # TODO && git push'
            }

            publishers {
                downstreamParameterized {
                    trigger(integrationTests) {
                        condition('SUCCESS')
                        parameters {
                            predefinedProp("ARTIFACT_BUILD_NUMBER", "\${BUILD_NUMBER}")
                            predefinedProp("ARTIFACT_VERSION", "\${RELEASE_VERSION}")
                            predefinedProp("ARTIFACT_GROUP_ID", "\${PROJECT_GROUP_ID}")
                            predefinedProp("ARTIFACT_ARTIFACT_ID", "\${PROJECT_ARTIFACT_ID}")
                        }
                    }
                }
            }
        }

        job(integrationTests) {
            description("Job for running integration tests for $branchPath")

            scm {
                github(repo, branch)
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
            publishers {
                downstreamParameterized {
                    trigger(promoteToRelease) {
                        condition('SUCCESS')
                        predefinedProp("ARTIFACT_BUILD_NUMBER", "\${BUILD_NUMBER}")
                        predefinedProp("ARTIFACT_GROUP_ID", "\${ARTIFACT_GROUP_ID}")
                        predefinedProp("ARTIFACT_ARTIFACT_ID", "\${ARTIFACT_ARTIFACT_ID}")
                        predefinedProp("ARTIFACT_VERSION", "\${ARTIFACT_VERSION}")
                    }
                }
            }
        }

        job(promoteToRelease) {
            description("Job for promoting successful $branchPath releases from the staging artifact repository to the public releases artifact repository")

            wrappers {
                // clean workspace
                preBuildCleanup()
            }

            steps {
                artifactPromotion {
                    groupId("\${ARTIFACT_GROUP_ID}")
                    artifactId("\${ARTIFACT_ARTIFACT_ID}")
                    version("\${ARTIFACT_VERSION}")
                    extension("pom")
                    stagingRepository("http://192.168.99.100:32770/content/repositories/staging/", "deployment", "deployment123", true)
                    releaseRepository("http://192.168.99.100:32770/content/repositories/releases/", "deployment", "deployment123")
                    debug(true)
                }
            }
        }
    }
}
