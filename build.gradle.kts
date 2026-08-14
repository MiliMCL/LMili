import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent

plugins {
    java
    id("moe.luminolmc.hyacinthusweight.patcher")
}

paperweight {
    upstreams.register("folia") {
        repo = github("PaperMC", "Folia")
        ref = providers.gradleProperty("foliaRef")

        patchFile {
            path = "folia-server/build.gradle.kts"
            outputFile = file("lmili-server/build.gradle.kts.base")
            patchFile = file("lmili-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "folia-api/build.gradle.kts"
            outputFile = file("lmili-api/build.gradle.kts.base")
            patchFile = file("lmili-api/build.gradle.kts.patch")
        }

        patchRepo("paperApi") {
            upstreamPath = "paper-api"
            patchesDir = file("lmili-api/paper-patches")
            outputDir = file("paper-api")
        }
    }
}

val paperMavenPublicUrl = "https://repo.papermc.io/repository/maven-public/"
val menthaMavenPublicUrl = "https://repo.menthamc.org/repository/maven-public/"

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    // Mili - explicitly bind JavaCompile to the JDK 25 toolchain compiler,
    // otherwise forked compilation may pick a stale JDK on CI runners
    // ("release version 25 not supported")
    val javaToolchains = extensions.getByType<JavaToolchainService>()
    tasks.withType<JavaCompile>().configureEach {
        javaCompiler = javaToolchains.compilerFor {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
        maven(paperMavenPublicUrl)
        maven(menthaMavenPublicUrl)
    }

    dependencies {
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile>().configureEach {
        options.encoding = Charsets.UTF_8.name()
        options.release = 25
        options.isFork = true
    }
    tasks.withType<Javadoc>().configureEach {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources>().configureEach {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            // Only configure MenthaMC if credentials are available (skip in CI for publishing to Sonatype)
            val mavenUser = System.getenv("PRIVATE_MAVEN_REPO_USERNAME")
            val mavenPass = System.getenv("PRIVATE_MAVEN_REPO_PASSWORD")
            if (!mavenUser.isNullOrBlank() && !mavenPass.isNullOrBlank()) {
                maven("https://repo.menthamc.org/repository/maven-snapshots/") {
                    name = "MenthaMC"
                    credentials(PasswordCredentials::class) {
                        username = mavenUser
                        password = mavenPass
                    }
                }
            }
        }
    }

    tasks.withType<Javadoc>().configureEach {
        options {
            (this as StandardJavadocDocletOptions).apply {
                addBooleanOption("Xdoclint:none", true)
                addStringOption("Xdoclint:none", "-quiet")
                addBooleanOption("linkTODO", false)
            }
        }
        isFailOnError = false
    }
}