import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import org.apache.tools.ant.filters.ReplaceTokens

plugins {
    `java-library`
    alias(libs.plugins.lavalink.gradle.plugin)
    alias(libs.plugins.maven.publish.base)
    id("com.github.johnrengelman.shadow")
}

lavalinkPlugin {
    name = "youtube-plugin"
    path = "dev.lavalink.youtube.plugin"
    apiVersion = libs.versions.lavalink
    serverVersion = "4.0.7"
    configurePublishing = false
}

base {
    archivesName = "youtube-plugin"
}

dependencies {
    implementation(projects.common)
    implementation(projects.v2)
    compileOnly(libs.lavalink.server)
    compileOnly(libs.lavaplayer.ext.youtube.rotator)
    implementation(libs.nanojson)
    compileOnly(libs.slf4j)
    compileOnly(libs.annotations)

    // Embed these dependencies into the plugin jar
    implementation(libs.bundles.graaljs)
    implementation(group = "io.github.bonede", name = "tree-sitter", version = "0.25.3")
    implementation(group = "io.github.bonede", name = "tree-sitter-javascript", version = "0.23.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

mavenPublishing {
    coordinates("dev.lavalink.youtube", "youtube-plugin", version.toString())
    configure(JavaLibrary(JavadocJar.None(), sourcesJar = false))
}

afterEvaluate {
    publishing {
        publications.named<MavenPublication>("maven") {
            artifact(tasks.shadowJar)
        }
    }
}

tasks.shadowJar {
    archiveClassifier.set("") // Produce youtube-plugin.jar instead of youtube-plugin-all.jar
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    relocate("lib", "")
}

// Lavalink requires the plugin to be a jar task
tasks.jar {
    enabled = false // We're using shadowJar instead
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks {
    processResources {
        filter<ReplaceTokens>(
            "tokens" to mapOf(
                "version" to project.version
            )
        )
    }
}
