import jetbrains.buildServer.configs.kotlin.v2018_2.*
import jetbrains.buildServer.configs.kotlin.v2018_2.buildSteps.*

/*
The settings script is an entry point for defining a TeamCity
project hierarchy. The script should contain a single call to the
project() function with a Project instance or an init function as
an argument.

VcsRoots, BuildTypes, Templates, and subprojects can be
registered inside the project using the vcsRoot(), buildType(),
template(), and subProject() methods respectively.

To debug settings scripts in command-line, run the

    mvnDebug org.jetbrains.teamcity:teamcity-configs-maven-plugin:generate

command and attach your debugger to the port 8000.

To debug in IntelliJ Idea, open the 'Maven Projects' tool window (View
-> Tool Windows -> Maven Projects), find the generate task node
(Plugins -> teamcity-configs -> teamcity-configs:generate), the
'Debug' option is available in the context menu for the task.
*/

version = "2018.2"
val versionParameter = "releaseVersion"
val publishVersion = "0.1.0"

val platforms = listOf("Windows", "Linux", "Mac OS X")

project {
    // Disable editing of project and build settings from the UI to avoid issues with TeamCity
    params {
        param("teamcity.ui.settings.readOnly", "true")
    }

    val builds = platforms.map { build(it) }

    val deployConfigure = deployConfigure()
    val deploys = platforms.map { deploy(it, deployConfigure) }/*.apply {
        reduce { previous, current ->
            current.apply {
                // Dependency on previous is needed to serialize deployment builds
                // TODO: Uploading can be made parallel if we create a version in configure
                dependsOnSnapshot(previous)
            }
        }
    }*/

    val deployPublish = deployPublish(deployConfigure).apply {
        deploys.forEach {
            dependsOnSnapshot(it)
        }
    }

    buildTypesOrder = builds + deployPublish + deployConfigure + deploys
}


fun Project.build(platform: String) = platform(platform, "Build") {
    /*
        triggers {
            vcs {
                triggerRules = """
                    -:*.md
                    -:.gitignore
                """.trimIndent()
            }
        }
    */

    // How to build a project
    steps {
        gradle {
            name = "Build and Test $platform Binaries"
            jdkHome = "%env.JDK_18_x64%"
            jvmArgs = "-Xmx1g"
            // --continue is needed to run tests on all platforms even if one platform fails
            tasks = "clean build --continue"
            buildFile = ""
            gradleWrapperPath = ""
        }
    }

    // What files to publish as build artifacts
    artifactRules = """
        +:**/build/libs/*.jar
        +:**/build/libs/*.klib
    """.trimIndent()

}

fun BuildType.dependsOn(build: BuildType, configure: Dependency.() -> Unit) =
    apply {
        dependencies.dependency(build, configure)
    }

fun BuildType.dependsOnSnapshot(build: BuildType, configure: SnapshotDependency.() -> Unit = {}) = apply {
    dependencies.dependency(build) {
        snapshot {
            configure()
            onDependencyFailure = FailureAction.CANCEL
            onDependencyCancel = FailureAction.CANCEL
        }
    }
}

fun Project.deployConfigure() = BuildType {
    id("Deploy_Configure")
    this.name = "Deploy (Configure)"
    commonConfigure()
    buildNumberPattern = "$publishVersion-dev-%build.counter%"

    params {
        // enable editing of this configuration to set up things
        param("teamcity.ui.settings.readOnly", "false")

        param("bintray-org", "orangy")
        param("bintray-repo", "maven")
        param("bintray-user", "orangy")
        password("bintray-key", "credentialsJSON:9a48193c-d16d-46c7-8751-2fb434b09e07")
        param("bintray-package", "kotlinx-files")

        param(versionParameter, "%build.number%")
    }

    requirements {
        // Require Linux for configuration build
        contains("teamcity.agent.jvm.os.name", "Linux")
    }

    steps {
        // Verify that gradle can configure itself and there are no issue with gradle script
        gradle {
            name = "Verify Gradle Configuration"
            tasks = "clean model"
            buildFile = ""
            jdkHome = "%env.JDK_18%"
        }

        // Create version in bintray to run deploy tasks in parallel for platforms
        script {
            name = "Create Version on Bintray"
            // add to JSON: , "released":"%system.build.start.date%"
            // TODO: Figure out how to get the build date :(
            scriptContent =
                """
echo '{"name": "%$versionParameter%", "desc": ""}'                    
curl -d '{"name": "%$versionParameter%", "desc": ""}' --fail --user %bintray-user%:%bintray-key% -H "Content-Type: application/json" -X POST https://api.bintray.com/packages/%bintray-org%/%bintray-repo%/%bintray-package%/versions
""".trimIndent()
        }
    }
}.also { buildType(it) }

fun Project.deployPublish(configureBuild: BuildType) = BuildType {
    id("Deploy_Publish")
    this.name = "Deploy (Publish)"
    type = BuildTypeSettings.Type.COMPOSITE
    buildNumberPattern = "%releaseVersion% (%build.counter%)"
    params {
        param(versionParameter, "${configureBuild.depParamRefs.buildNumber}")
    }
    commonConfigure()
}.also { buildType(it) }.dependsOnSnapshot(configureBuild)


fun Project.deploy(platform: String, configureBuild: BuildType) = platform(platform, "Deploy") {
    type = BuildTypeSettings.Type.DEPLOYMENT
    enablePersonalBuilds = false
    maxRunningBuilds = 1
    buildNumberPattern = "%releaseVersion% (%build.counter%)"
    params {
        param(versionParameter, "${configureBuild.depParamRefs.buildNumber}")
    }

    vcs {
        cleanCheckout = true
    }

    // How to build a project
    steps {
        gradle {
            name = "Deploy $platform Binaries"
            jdkHome = "%env.JDK_18_x64%"
            jvmArgs = "-Xmx1g"
            gradleParams = "-Pdeploy=true -P$versionParameter=%$versionParameter%"
            tasks = "clean build publishToMavenLocal"
            buildFile = ""
            gradleWrapperPath = ""
        }
    }
}.dependsOnSnapshot(configureBuild)

fun Project.platform(platform: String, name: String, configure: BuildType.() -> Unit) = BuildType {
    // ID is prepended with Project ID, so don't repeat it here
    // ID should conform to identifier rules, so just letters, numbers and underscore
    id("${name}_${platform.substringBefore(" ")}")
    // Display name of the build configuration
    this.name = "$name ($platform)"

    requirements {
        contains("teamcity.agent.jvm.os.name", platform)
    }

    params {
        // This parameter is needed for macOS agent to be compatible
        param("env.JDK_17", "")
    }

    commonConfigure()
    configure()
}.also { buildType(it) }


fun BuildType.commonConfigure() {
    requirements {
        noLessThan("teamcity.agent.hardware.memorySizeMb", "6144")
    }

    // Allow to fetch build status through API for badges
    allowExternalStatus = true

    // Configure VCS, by default use the same and only VCS root from which this configuration is fetched
    vcs {
        root(DslContext.settingsRoot)
    }

    features {
        feature {
            type = "xml-report-plugin"
            param("xmlReportParsing.reportType", "junit")
            param("xmlReportParsing.reportDirs", "+:**/build/reports/*.xml")
        }
    }
}
