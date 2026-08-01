import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.work.DisableCachingByDefault
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskAction
import org.cyclonedx.gradle.BaseCyclonedxTask
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.License
import org.cyclonedx.model.LicenseChoice
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Locale

plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
    signing
    id("com.gradle.plugin-publish") version "2.1.1"
    id("org.jetbrains.dokka") version "2.2.0"
    id("org.cyclonedx.bom") version "3.3.0"
}

@DisableCachingByDefault(because = "Release metadata verification has no outputs")
abstract class VerifyReleaseMetadataTask : DefaultTask() {
    @get:Input
    abstract val releaseVersion: Property<String>

    @get:Input
    abstract val releaseTag: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val changelogFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val resolvedVersion = releaseVersion.get()
        require(
            resolvedVersion.length <= MAX_RELEASE_COORDINATE_LENGTH &&
                STRICT_SEMVER.matches(resolvedVersion),
        ) {
            "Release version is not a supported semantic version."
        }
        require(!resolvedVersion.uppercase(Locale.ROOT).endsWith("SNAPSHOT")) {
            "Refusing to publish snapshot version '$resolvedVersion'. Pass -PkiteSsot.version from an exact v<version> tag."
        }
        val tag = releaseTag.orNull
            ?.takeIf(String::isNotBlank)
            ?: error(
                "Refusing to publish without an exact release tag. Set GITHUB_REF_NAME or " +
                    "-PkiteSsot.releaseTag=v$resolvedVersion."
            )
        require(
            tag.length <= MAX_RELEASE_COORDINATE_LENGTH + 1 &&
                tag.startsWith("v") &&
                STRICT_SEMVER.matches(tag.drop(1)),
        ) {
            "Release tag is not a valid v-prefixed semantic version."
        }
        require(tag == "v$resolvedVersion") {
            "Release tag does not match artifact version '$resolvedVersion' (expected v$resolvedVersion)."
        }
        val changelog = readChangelog(changelogFile.get().asFile.toPath())
        val exactHeading = Regex(
            "(?m)^##[ \\t]+(?:\\[${Regex.escape(resolvedVersion)}]|${Regex.escape(resolvedVersion)})(?:[ \\t]*$)",
        )
        require(exactHeading.containsMatchIn(changelog)) {
            "CHANGELOG.md has no heading/link entry for $resolvedVersion."
        }
    }

    private fun readChangelog(path: Path): String {
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "CHANGELOG.md must be a regular file and must not be a symbolic link."
        }

        val bytes = ByteBuffer.allocate(MAX_CHANGELOG_BYTES + 1)
        try {
            Files.newByteChannel(
                path,
                setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS),
            ).use { channel ->
                while (bytes.hasRemaining()) {
                    if (channel.read(bytes) < 0) break
                }
            }
        } catch (exception: IOException) {
            throw GradleException("CHANGELOG.md could not be read safely.", exception)
        }

        require(bytes.position() <= MAX_CHANGELOG_BYTES) {
            "CHANGELOG.md exceeds the $MAX_CHANGELOG_BYTES-byte release-verification limit."
        }
        bytes.flip()
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(bytes)
                .toString()
        } catch (exception: CharacterCodingException) {
            throw GradleException("CHANGELOG.md must be valid UTF-8.", exception)
        }
    }

    companion object {
        private const val MAX_CHANGELOG_BYTES = 1_048_576
        private const val MAX_RELEASE_COORDINATE_LENGTH = 256
        private val STRICT_SEMVER = Regex(
            """(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)""" +
                """(?:-(?:(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))""" +
                """(?:\.(?:0|[1-9]\d*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*))*)?""" +
                """(?:\+[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*)?""",
        )
    }
}

@DisableCachingByDefault(because = "Release signing verification has no outputs")
abstract class VerifyReleaseSigningTask : DefaultTask() {
    @get:Input
    abstract val signingKeyPresent: Property<Boolean>

    @TaskAction
    fun verify() {
        require(signingKeyPresent.get()) {
            "Release signing key is missing. Set SIGNING_KEY (and SIGNING_PASSWORD when encrypted)."
        }
    }
}

group = "io.github.yuroyami"
version = providers.gradleProperty("kiteSsot.version").getOrElse("0.0.0-SNAPSHOT")

java {
    withSourcesJar()
    withJavadocJar()
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {
        enabled.set(true)
    }
}

// Both tasks address the checked-in api/ directory. Make their relationship
// explicit so `updateKotlinAbi checkKotlinAbi` is valid under Gradle 9's strict
// task-output validation as well as convenient for maintainers.
tasks.named("checkKotlinAbi") {
    mustRunAfter("updateKotlinAbi")
}

// Compile with the producer's JDK 21 toolchain but emit Java-17 bytecode so a
// consumer Gradle daemon on JDK 17 can load the plugin without linkage failures.
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

repositories {
    mavenCentral()
    google()
    gradlePluginPortal()
}

// AGP 9 narrowed several classic DSL method return descriptors. Code compiled
// against 9.1 cannot invoke those methods on AGP 8 even though the source API
// looks compatible, so the compact AGP-8 adapter is compiled against the exact
// supported floor and packaged into the main plugin JAR.
val agp8AdapterSourceSet = sourceSets.create("agp8Adapter") {
    compileClasspath += sourceSets.main.get().output
}
tasks.named<JavaCompile>(agp8AdapterSourceSet.compileJavaTaskName) {
    dependsOn(tasks.named("classes"))
}

dependencyLocking {
    lockAllConfigurations()
}

dependencies {
    // Consumers bring their own AGP / Kotlin Gradle plugin; we only need types
    // at compile time. The full KGP (not just -api) is needed for the concrete
    // KotlinMultiplatformExtension used by interop opt-in + web worker wiring.
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.kotlin.gradle.plugin.api)
    compileOnly(libs.kotlin.gradle.plugin)

    testImplementation(libs.junit.jupiter)
    testImplementation(gradleTestKit())
    testRuntimeOnly(libs.junit.platform.launcher)

    add(agp8AdapterSourceSet.compileOnlyConfigurationName, gradleApi())
    add(agp8AdapterSourceSet.compileOnlyConfigurationName, libs.android.gradle.api.floor)
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("agp-compatibility")
    }
}

// Real AGP consumer builds are deliberately kept out of the fast unit-test
// task: each case starts another Gradle distribution and resolves a complete
// Android plugin. CI runs this task once on Linux while `test` remains suitable
// for the full OS/JDK/Gradle matrix.
val testSourceSet = sourceSets.named("test")
val pluginJar = tasks.named<Jar>("jar")
tasks.register<Test>("agpCompatibilityTest") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs real AGP floor/current consumer-build compatibility fixtures."
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("agp-compatibility")
    }
    dependsOn(pluginJar)
    doFirst {
        systemProperty("kitessot.test.pluginJar", pluginJar.get().archiveFile.get().asFile.absolutePath)
    }
    shouldRunAfter(tasks.named("test"))
}

tasks.withType<Jar>().configureEach {
    // The unprivileged and protected release jobs build independently. Make
    // their archive bytes reproducible so checksum equality proves that the
    // reviewed candidate, rather than merely equivalent sources, is published.
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(layout.projectDirectory.file("LICENSE")) {
        into("META-INF")
    }
    manifest {
        attributes(
            "Implementation-Title" to "KiteSSOT Gradle Plugin",
            "Implementation-Version" to project.version.toString(),
            "Implementation-Vendor" to "yuroyami",
            "Build-Revision" to providers.environmentVariable("GITHUB_SHA").getOrElse("unknown"),
            "Automatic-Module-Name" to "io.github.yuroyami.kitessot",
        )
    }
}

tasks.named<Jar>("jar") {
    from(agp8AdapterSourceSet.output)
}

tasks.named<Jar>("sourcesJar") {
    from(agp8AdapterSourceSet.allSource)
}

// The published plugin has no bundled/runtime Maven dependencies: AGP and KGP
// are compile-only host integrations. Restrict the release SBOM to the actual
// runtime graph instead of reporting tests, Dokka, and build tooling as shipped.
tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    includeConfigs.set(listOf("runtimeClasspath"))
    includeMetadataResolution.set(false)
}

tasks.withType<BaseCyclonedxTask>().configureEach {
    licenseChoice.set(
        LicenseChoice().apply {
            addLicense(
                License().apply {
                    name = "Apache-2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                },
            )
        },
    )
}

// Shared Kite theme. Sources live in ../_kite-docs; ./_kite-docs/sync.sh copies
// them here, so this repo still builds standalone from a fresh clone.
dokka {
    moduleName.set("KiteSSOT")
    pluginsConfiguration.html {
        customStyleSheets.from(layout.projectDirectory.file("docs/api-theme/kite.css"))
        templatesDir.set(layout.projectDirectory.dir("dokka-templates"))
        footerMessage.set("Apache-2.0 · KiteSSOT is part of the Kite family.")
    }
}

// java-gradle-plugin publishes a javadoc artifact, but Java's Javadoc task does
// not understand Kotlin. Fill that artifact with the actual Dokka HTML/KDoc.
tasks.named<Jar>("javadocJar") {
    dependsOn("dokkaGeneratePublicationHtml")
    from(layout.buildDirectory.dir("dokka/html"))
}

// KGP is compileOnly, so TestKit's injected plugin classpath (built from
// runtimeClasspath) wouldn't include it — functional tests that apply
// kotlin("multiplatform") in fixtures need it added explicitly. This also puts
// KGP in the SAME classloader as the plugin under test, matching the documented
// consumer setup (kotlin declared in the root plugins block).
val testKitPluginClasspath: Configuration by configurations.creating
dependencies {
    testKitPluginClasspath(libs.kotlin.gradle.plugin)
}
tasks.named<org.gradle.plugin.devel.tasks.PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(testKitPluginClasspath)
}

gradlePlugin {
    website = "https://github.com/yuroyami/KiteSSOT"
    vcsUrl = "https://github.com/yuroyami/KiteSSOT.git"
    plugins {
        create("kiteSsot") {
            id = "io.github.yuroyami.kitessot"
            implementationClass = "io.github.yuroyami.kitessot.KiteSsotPlugin"
            displayName = "KiteSSOT"
            description = "Single source of truth for Kotlin Multiplatform app configuration (identity, version, bundle ids, locales, launcher assets, generated BuildConfig) propagated to Android + iOS."
            tags = listOf("kotlin", "kotlin-multiplatform", "kmp", "android", "ios", "configuration", "versioning")
        }
    }
}

// Apache-2.0 licence metadata on every published POM.
publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set("KiteSSOT Gradle Plugin")
            description.set(
                "A safe, explicit single-source application model and platform adapter for Kotlin Multiplatform projects."
            )
            url.set("https://github.com/yuroyami/KiteSSOT")
            inceptionYear.set("2026")
            licenses {
                license {
                    name = "The Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    distribution = "repo"
                }
            }
            developers {
                developer {
                    id.set("yuroyami")
                    name.set("Yuro Yami")
                    url.set("https://github.com/yuroyami")
                    roles.addAll("developer", "maintainer")
                }
            }
            scm {
                connection.set("scm:git:https://github.com/yuroyami/KiteSSOT.git")
                developerConnection.set("scm:git:ssh://git@github.com/yuroyami/KiteSSOT.git")
                url.set("https://github.com/yuroyami/KiteSSOT")
                tag.set("v${project.version}")
            }
            issueManagement {
                system.set("GitHub Issues")
                url.set("https://github.com/yuroyami/KiteSSOT/issues")
            }
            ciManagement {
                system.set("GitHub Actions")
                url.set("https://github.com/yuroyami/KiteSSOT/actions")
            }
        }
    }
    // Keep GitHub Packages as a secondary channel (internal / pre-release builds).
    // Plugin Portal is the primary public distribution, set up by plugin-publish.
    repositories {
        // A complete, inspectable publication staging area used by release CI for
        // checksum/provenance generation before either remote channel is touched.
        maven {
            name = "ReleaseStaging"
            url = layout.buildDirectory.dir("staging-repository").get().asFile.toURI()
        }
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/yuroyami/KiteSSOT")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR")
                    .filter { it.isNotBlank() }
                    .orElse(providers.gradleProperty("gpr.user").filter { it.isNotBlank() })
                    .orNull
                password = providers.environmentVariable("GITHUB_TOKEN")
                    .filter { it.isNotBlank() }
                    .orElse(providers.gradleProperty("gpr.key").filter { it.isNotBlank() })
                    .orNull
            }
        }
    }
}

// Secret-free release candidate assembled from the same Gradle-generated
// publication payloads later signed and published by Maven Publish. Release CI
// transfers this immutable directory across the protected-environment boundary
// and compares every payload byte before any remote publication.
tasks.register<Sync>("stageUnsignedReleaseCandidate") {
    group = "publishing"
    description = "Stage unsigned core Maven payloads for protected release verification."
    dependsOn(
        "jar",
        "sourcesJar",
        "javadocJar",
        "generatePomFileForPluginMavenPublication",
        "generateMetadataFileForPluginMavenPublication",
        "generatePomFileForKiteSsotPluginMarkerMavenPublication",
    )

    val releaseVersion = project.version.toString()
    val implementationPath = "io/github/yuroyami/kitessot/$releaseVersion"
    val markerPath =
        "io/github/yuroyami/kitessot/io.github.yuroyami.kitessot.gradle.plugin/$releaseVersion"

    into(layout.buildDirectory.dir("unsigned-release-candidate"))
    into(implementationPath) {
        from(tasks.named<Jar>("jar").flatMap { it.archiveFile })
        from(tasks.named<Jar>("sourcesJar").flatMap { it.archiveFile })
        from(tasks.named<Jar>("javadocJar").flatMap { it.archiveFile })
        from(layout.buildDirectory.file("publications/pluginMaven/pom-default.xml")) {
            rename { "kitessot-$releaseVersion.pom" }
        }
        from(layout.buildDirectory.file("publications/pluginMaven/module.json")) {
            rename { "kitessot-$releaseVersion.module" }
        }
    }
    into(markerPath) {
        from(layout.buildDirectory.file("publications/kiteSsotPluginMarkerMaven/pom-default.xml")) {
            rename { "io.github.yuroyami.kitessot.gradle.plugin-$releaseVersion.pom" }
        }
    }
}

val releaseSigningKey = providers.environmentVariable("SIGNING_KEY")
    .filter { it.isNotBlank() }
    .orElse(providers.gradleProperty("signingKey").filter { it.isNotBlank() })
val releaseSigningPassword = providers.environmentVariable("SIGNING_PASSWORD")
    .orElse(providers.gradleProperty("signingPassword"))
val releaseSigningKeyId = providers.environmentVariable("SIGNING_KEY_ID")
    .filter { it.isNotBlank() }
    .orElse(providers.gradleProperty("signingKeyId").filter { it.isNotBlank() })

signing {
    if (releaseSigningKey.isPresent) {
        if (releaseSigningKeyId.isPresent) {
            useInMemoryPgpKeys(
                releaseSigningKeyId.get(),
                releaseSigningKey.get(),
                releaseSigningPassword.orNull,
            )
        } else {
            useInMemoryPgpKeys(
                releaseSigningKey.get(),
                releaseSigningPassword.orNull,
            )
        }
        sign(publishing.publications)
    }
}

val releaseVersionValue = version.toString()
val releaseTagProvider = providers.environmentVariable("GITHUB_REF_NAME")
    .filter { it.isNotBlank() }
    .orElse(providers.gradleProperty("kiteSsot.releaseTag").filter { it.isNotBlank() })

tasks.register<VerifyReleaseMetadataTask>("verifyReleaseMetadata") {
    group = "verification"
    description = "Verify the publish version, Git tag, changelog, and release coordinates agree."
    releaseVersion.set(releaseVersionValue)
    releaseTag.set(releaseTagProvider.orElse(""))
    changelogFile.set(layout.projectDirectory.file("CHANGELOG.md"))
}

tasks.register<VerifyReleaseSigningTask>("verifyReleaseSigning") {
    group = "verification"
    description = "Require in-memory PGP signing material before a remote release."
    signingKeyPresent.set(releaseSigningKey.map { it.isNotBlank() }.orElse(false))
}

tasks.matching {
    it.name == "publishPlugins" ||
        (it.name.startsWith("publish") && it.name.contains("Repository"))
}.configureEach {
    dependsOn("verifyReleaseMetadata", "verifyReleaseSigning")
}
