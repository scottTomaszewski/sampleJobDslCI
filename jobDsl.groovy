def masterBranches = ['master-v8', 'master-v9']
def modules = [
        [name: 'ds', repo: 'dis', branches: masterBranches],
        [name: 'ms', repo: 'ms', branches: masterBranches],
        [name: 'ba', repo: 'ba', branches: masterBranches],
        //[name: 'if', repo: 'ba', branches: ['master']],
        //[name: 'cs', repo: 'ba', branches: ['master']],
        //[name: 'is', repo: 'ba', branches: ['master']],
        //[name: 'ts', repo: 'ba', branches: ['master']],
        //[name: 'dm', repo: 'ba', branches: ['master']],
        //[name: 'io', repo: 'ba', branches: ['master']],
]

def nexusUrl = "http://192.168.99.100:32770"
def buildModulesBom = "buildBom"
def promoteBom = "promoteBom"


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
        def buildAndReleaseToStaging = "$branchPath/build-to-staging"
        def integrationTests = "$branchPath/integration-tests"
        def promoteToRelease = "$branchPath/promote-to-release"

        def pipelineClosure = {
            filterBuildQueue()
            filterExecutors()
            title("$branchPath CI Pipeline")
            displayedBuilds(5)
            selectedJob(buildAndReleaseToStaging)
            alwaysAllowManualTrigger()
            showPipelineParameters()
            refreshFrequency(5)
        }

        buildPipelineView("$modulePath/$branch pipeline", pipelineClosure)
        buildPipelineView("$branchPath/pipeline", pipelineClosure)

        job(buildAndReleaseToStaging) {
            description("Job for testing $branchPath then releasing successful builds to the staging artifact repository")

            scm {
                github(repo, branch)
            }

            triggers {
                scm '* * * * *'
            }

            wrappers cleanAndAddMavenSettings()

            steps {
                def script = """
                # prepare git
                git config user.name "Jenkins"
                git config user.email "DevOps_Team@FIXME.com"
                git config push.default simple

                # git plugin checkouts to detached head, fix that
                # https://issues.jenkins-ci.org/browse/JENKINS-6856
                git checkout $branch

                # figure out git commit count
                GIT_COMMIT_COUNT=`git rev-list --all --count`

                # figure out csp version from branch name
                GIT_BRANCH=`git rev-parse --abbrev-ref HEAD`
                VERSION_REGEX="[0-9A-Za-z-]*-v\\([0-9]*\\)[0-9A-Za-z-]*"
                PLATFORM_VER_VAR=`echo \$GIT_BRANCH | sed -e "s#\$VERSION_REGEX#\\1#"`

                # evaluate cdm version from property
                CDM_VAR=`mvn help:evaluate -Dexpression=cdm-version|grep -Ev \'(^\\[|Download\\w+:)\'`

                # evaluate declared project info
                PROJECT_VERSION_VAR=`mvn help:evaluate -Dexpression=project.version|grep -Ev \'(^\\[|Download\\w+:)\'`
                PROJECT_GROUP_ID_VAR=`mvn help:evaluate -Dexpression=project.groupId|grep -Ev '(^\\[|Download\\w+:)'`
                PROJECT_ARTIFACT_ID_VAR=`mvn help:evaluate -Dexpression=project.artifactId|grep -Ev '(^\\[|Download\\w+:)'`

                # remove "-SNAPSHOT" from project version
                WITHOUT_SNAPSHOT=\${PROJECT_VERSION_VAR%-SNAPSHOT}

                # release version as CSP.MAJOR.MINOR.[GIT_COMMIT_COUNT]SUFFIX
                SEMVER="[^0-9]*\\([0-9]*\\)[.]\\([0-9]*\\)[.]\\([0-9]*\\)\\([0-9A-Za-z-]*\\)"
                RELEASE_VER_VAR=`echo \$WITHOUT_SNAPSHOT | sed -e "s#\$SEMVER#\${PLATFORM_VER_VAR}.\\1.\\2.\${GIT_COMMIT_COUNT}\\4#"`

                # next version as MAJOR.MINOR.[GIT_COMMIT_COUNT+1].SUFFIX
                NEXT_VER_VAR=`echo \$PROJECT_VERSION_VAR | sed -e "s#\$SEMVER#\\1.\\2.\$((GIT_COMMIT_COUNT+1))\\4#"`

                # create a branch for safekeeping
                git checkout -b staging-v\$RELEASE_VER_VAR

                # Add properties for EnvInject jenkins plugin
                echo "CDM=\$CDM_VAR" >> env.properties
                echo "PLATFORM_VERSION=\$PLATFORM_VER_VAR" >> env.properties
                echo "PROJECT_VERSION=\$PROJECT_VERSION_VAR" >> env.properties
                echo "PROJECT_GROUP_ID=\$PROJECT_GROUP_ID_VAR" >> env.properties
                echo "PROJECT_ARTIFACT_ID=\$PROJECT_ARTIFACT_ID_VAR" >> env.properties
                echo "RELEASE_VERSION=\$RELEASE_VER_VAR" >> env.properties
                echo "NEXT_VERSION=\$NEXT_VER_VAR" >> env.properties

                # print out description for Description Setter jenkins plugin
                echo "DESCRIPTION v\$RELEASE_VER_VAR (CSP=v\${#CSP_VERSION}, CDM=\$CDM_VAR)"
                """
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
                            predefinedProp("PLATFORM_VERSION", "\${PLATFORM_VERSION}")
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

            wrappers cleanAndAddMavenSettings()

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
                        parameters {
                            predefinedProp("ARTIFACT_BUILD_NUMBER", "\${BUILD_NUMBER}")
                            predefinedProp("ARTIFACT_GROUP_ID", "\${ARTIFACT_GROUP_ID}")
                            predefinedProp("ARTIFACT_ARTIFACT_ID", "\${ARTIFACT_ARTIFACT_ID}")
                            predefinedProp("ARTIFACT_VERSION", "\${ARTIFACT_VERSION}")
                            predefinedProp("PLATFORM_VERSION", "\${PLATFORM_VERSION}")
                        }
                    }
                }
            }
        }

        job(promoteToRelease) {
            description("Job for promoting successful $branchPath releases from the staging artifact repository to the public releases artifact repository")

            wrappers cleanAndAddMavenSettings()

            steps promoteArtifact("jar", nexusUrl, false)

            publishers {
                // Trigger new platform integration flow
                // NOTE: if you change this, you also need to change the platformFolder variable
                downstream("${branch} Platform Integration/${buildModulesBom}", 'SUCCESS')
            }
        }
    }
}

masterBranches.each { masterBranch ->
    // identify platform version from branch name
    def matcher = masterBranch =~ ".*-v([0-9]*)"
    def platformVersion = matcher[0][1]
    // NOTE: if you change this, you also need to change the downstream from promoteToRelease
    def platformFolder = "${masterBranch} Platform Integration"

    folder(platformFolder) {
        description "Jobs associated with the ${masterBranch} branches for all modules"
    }

    def buildBomJob = "${platformFolder}/${buildModulesBom}"
    def promoteBomToReleaseJob = "${platformFolder}/${promoteBom}"

    buildPipelineView("${platformFolder}/pipeline") {
        filterBuildQueue()
        filterExecutors()
        title("${platformFolder} CI Pipeline")
        displayedBuilds(5)
        selectedJob(buildBomJob)
        alwaysAllowManualTrigger()
        showPipelineParameters()
        refreshFrequency(5)
    }

    // Build bom with aggregate of all modules
    job(buildBomJob) {
        description("Job for build a bom that aggregates all the latest successful releases for modules on ${masterBranch}")

        scm {
            github("scottTomaszewski/bom")
        }

        wrappers cleanAndAddMavenSettings()

        steps {
            def script = """
                PROJECT_GROUP_ID_VAR=`mvn help:evaluate -Dexpression=project.groupId|grep -Ev '(^\\[|Download\\w+:)'`
                PROJECT_ARTIFACT_ID_VAR=`mvn help:evaluate -Dexpression=project.artifactId|grep -Ev '(^\\[|Download\\w+:)'`

                # Add properties for EnvInject jenkins plugin
                echo "PROJECT_GROUP_ID=\$PROJECT_GROUP_ID_VAR" >> env.properties
                echo "PROJECT_ARTIFACT_ID=\$PROJECT_ARTIFACT_ID_VAR" >> env.properties
            """
            shell script

            environmentVariables {
                propertiesFile('env.properties')
            }

            // insert platform version into each module dependency version
            // ex: <version>PLATFORM_VERSION</version> will become <version>8</version>
            maven("-PbuildBom -Dversion.platform=${platformVersion}")

            // update module versions to pull latest for their major version
            // ex: <version>8</version> will upgrade to <version>8.1.2.3</version>
            maven("versions:use-latest-releases -DallowMajorUpdates=false -U -s \${SETTINGS_CONFIG}")

            // bom version will be PLATFORM_VERSION.BUILD_NUMBER
            def RELEASE_VERSION = "${platformVersion}.\${BUILD_NUMBER}"

            buildDescription("", "$RELEASE_VERSION")
            wrappers {
                buildName('#${BUILD_NUMBER} - $RELEASE_VERSION')
            }

            // set release version on poms (temp: add branchPath since using same git repo) and commit
            maven("versions:set -DnewVersion=\'${RELEASE_VERSION}\'")

            // push up bom artifact to release repo
            maven("""deploy:deploy-file
                -Durl=${nexusUrl}/content/repositories/staging/
                -DrepositoryId=nexus
                -Dfile=pom.xml
                -DpomFile=pom.xml
                -s \${SETTINGS_CONFIG}
            """)
        }

        publishers {
            downstreamParameterized {
                trigger(promoteBomToReleaseJob) {
                    condition('SUCCESS')
                    parameters {
                        predefinedProp("ARTIFACT_VERSION", "${RELEASE_VERSION}")
                        predefinedProp("ARTIFACT_GROUP_ID", "\${PROJECT_GROUP_ID}")
                        predefinedProp("ARTIFACT_ARTIFACT_ID", "\${PROJECT_ARTIFACT_ID}")
                    }
                }
            }
        }
    }

    job(promoteBomToReleaseJob) {
        description("Job for promoting successful $masterBranch bom releases from the staging artifact repository to the public releases artifact repository")

        wrappers cleanAndAddMavenSettings()

        steps promoteArtifact("pom", nexusUrl, true)
    }
}

// ---------------
// COMMON HELPERS
// ---------------

// promotion step
// usage: step promoteArtifact("jar", "http://nexus.domain.com")
Closure promoteArtifact(String packaging, String nexusUrl, boolean isPom) {
    return {
        def script = """
            # pull down artifact
            mvn org.apache.maven.plugins:maven-dependency-plugin:copy \
                -Dartifact=\${ARTIFACT_GROUP_ID}:\${ARTIFACT_ARTIFACT_ID}:\${ARTIFACT_VERSION}:${packaging} \
                -DoutputDirectory=. \
                -s \${SETTINGS_CONFIG}"""
        if (!isPom) {
            script += """
            # pull down artifact pom
            mvn org.apache.maven.plugins:maven-dependency-plugin:copy \
                -Dartifact=\${ARTIFACT_GROUP_ID}:\${ARTIFACT_ARTIFACT_ID}:\${ARTIFACT_VERSION}:pom \
                -DoutputDirectory=. \
                -s \${SETTINGS_CONFIG}"""
        }
        script += """
            # push up artifact to release repo
            mvn deploy:deploy-file -Durl=${nexusUrl}/content/repositories/releases/ \
               -DrepositoryId=nexus \
               -Dfile=\${ARTIFACT_ARTIFACT_ID}-\${ARTIFACT_VERSION}.${packaging} \
               -s \${SETTINGS_CONFIG}"""
        script += isPom ? "" : " -DpomFile=\${ARTIFACT_ARTIFACT_ID}-\${ARTIFACT_VERSION}.pom"
        shell script
    }
}

// Clean workspace and add maven settings file
Closure cleanAndAddMavenSettings() {
    return {
        // clean workspace
        preBuildCleanup()

        // Add maven settings.xml from Managed Config Files
        configFiles {
            mavenSettings('MySettings') {
                variable('SETTINGS_CONFIG')
            }
        }
    }
}


