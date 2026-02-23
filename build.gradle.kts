plugins {
    `java-library`
    `maven-publish`
}

group = "io.github.term4"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("net.minestom:minestom:2026.02.19-1.21.11")
    testImplementation("net.minestom:minestom:2026.02.19-1.21.11")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "minestom-echo-fix"
            version = project.version.toString()
        }
    }

    tasks.test {
        // ExampleServer isn't a test — it's a runnable example
        enabled = false
    }
}