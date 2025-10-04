import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import org.apache.tools.ant.filters.ReplaceTokens

plugins {
    `java-library`
    alias(libs.plugins.maven.publish.base)
}

base {
    archivesName = "youtube-common"
}

dependencies {
    compileOnly(libs.lavaplayer.v1)

    api(libs.bundles.graaljs)
    api(group = "io.github.bonede", name = "tree-sitter", version = "0.25.3")
    api(group = "io.github.bonede", name = "tree-sitter-javascript", version = "0.23.1")
    implementation(libs.nanojson)
    compileOnly(libs.slf4j)
    compileOnly(libs.annotations)

    testImplementation(libs.lavaplayer.v1)
    testImplementation("org.apache.logging.log4j:log4j-core:2.19.0")
    testImplementation("org.apache.logging.log4j:log4j-slf4j2-impl:2.19.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.0-M1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.0-M1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

mavenPublishing {
    configure(JavaLibrary(JavadocJar.Javadoc()))
}

tasks {
    processResources {
        filter<ReplaceTokens>(
            "tokens" to mapOf(
                "version" to project.version
            )
        )
    }
    test {
        useJUnitPlatform() // Enable JUnit Platform for running JUnit 5 tests
    }
}
