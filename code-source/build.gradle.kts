plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
}

group = "codesource"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("codesource.ApplicationKt")
}

val ktorVersion = "3.6.0"
val jgitVersion = "7.8.0.202609011348-r"
val luceneVersion = "10.5.1"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("ch.qos.logback:logback-classic:1.5.38")
    implementation("org.eclipse.jgit:org.eclipse.jgit:$jgitVersion")
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:$jgitVersion")
    // Lets sshd read and use Ed25519 keys; without it only RSA and ECDSA work.
    runtimeOnly("org.bouncycastle:bcprov-jdk18on:1.85")
    runtimeOnly("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.apache.lucene:lucene-core:$luceneVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation("org.eclipse.jgit:org.eclipse.jgit.junit.ssh:$jgitVersion")
    testImplementation("org.eclipse.jgit:org.eclipse.jgit.junit.http:$jgitVersion")
    // The JGit test servers call JUnit 4's Assert, which they expect us to provide.
    testRuntimeOnly("junit:junit:4.13.2")
}

// The analyzers (analyzers/) are Python. They run in a venv that this build
// creates from analyzers/requirements.txt, with the machine's python3. It's
// an implementation detail of code-source: nothing outside configures it.
//
// A venv is created where it's used, never copied: a copy would hold a copy
// of the Python binary its symlink points at, which macOS can refuse to run
// (see docs/features/007-file-outlines.md, Implementation notes).
fun Exec.createsVenv(venv: Provider<Directory>) {
    val requirements = file("analyzers/requirements.txt")
    inputs.file(requirements)
    outputs.dir(venv)
    commandLine(
        "sh", "-c", """rm -rf "$1" && python3 -m venv "$1" && "$1/bin/pip" install --quiet --disable-pip-version-check -r "$2"""",
        "sh", venv.get().asFile.path, requirements.path,
    )
}

val analyzerVenv = layout.buildDirectory.dir("analyzer-venv")

val createAnalyzerVenv by tasks.registering(Exec::class) {
    description = "Creates the venv the analyzers run in, for tests and run."
    createsVenv(analyzerVenv)
}

// Tests and `run` use the analyzers in place. An installation has its own
// copy and venv, which code-source finds next to its jars (see Analyzers).
fun JavaForkOptions.useAnalyzersInPlace() {
    systemProperty("codesource.analyzers", file("analyzers").path)
    systemProperty("codesource.python", analyzerVenv.get().file("bin/python").asFile.path)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(createAnalyzerVenv)
    inputs.dir("analyzers")
    useAnalyzersInPlace()
}

tasks.named<JavaExec>("run") {
    dependsOn(createAnalyzerVenv)
    useAnalyzersInPlace()
}

distributions {
    main {
        contents {
            from("analyzers") {
                into("analyzers")
                exclude("**/__pycache__", "requirements.txt")
            }
        }
    }
}

val installAnalyzerVenv by tasks.registering(Exec::class) {
    description = "Creates the installation's venv for the analyzers."
    createsVenv(tasks.installDist.map { layout.projectDirectory.dir(it.destinationDir.path).dir("analyzers/venv") })
}

tasks.installDist {
    // -PinstallDir=<path> installs somewhere else: the image (Dockerfile)
    // installs at its runtime path, so the venv is created where it runs.
    providers.gradleProperty("installDir").orNull?.let { destinationDir = file(it) }
    // installDist replaces the installation's contents, but not its venv.
    preserve { include("analyzers/venv/**") }
    finalizedBy(installAnalyzerVenv)
}
