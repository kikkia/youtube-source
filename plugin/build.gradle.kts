import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import org.apache.tools.ant.filters.ReplaceTokens

plugins {
    `java-library`
    alias(libs.plugins.lavalink.gradle.plugin)
    alias(libs.plugins.maven.publish.base)
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

val embed by configurations.creating
dependencies {
    implementation(projects.common)
    implementation(projects.v2)
    compileOnly(libs.lavalink.server)
    compileOnly(libs.lavaplayer.ext.youtube.rotator)
    implementation(libs.nanojson)
    compileOnly(libs.slf4j)
    compileOnly(libs.annotations)

    // Embed these dependencies into the plugin jar
    embed(libs.bundles.graaljs)
    embed(group = "io.github.bonede", name = "tree-sitter", version = "0.25.3")
    embed(group = "io.github.bonede", name = "tree-sitter-javascript", version = "0.23.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

mavenPublishing {
    coordinates("dev.lavalink.youtube", "youtube-plugin", version.toString())
    configure(JavaLibrary(JavadocJar.None(), sourcesJar = false))
}

tasks.jar {
    dependsOn(":common:compileTestJava")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(embed.map { if (it.isDirectory) it else zipTree(it) })
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
