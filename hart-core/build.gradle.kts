plugins { kotlin("jvm") }
kotlin { jvmToolchain(21); sourceSets.getByName("test").kotlin.srcDir("../test-support") }
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
tasks.test { testLogging { events("passed", "skipped", "failed") } }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

tasks.register<JavaExec>("exportControlReview") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.hartbro.testing.ExportControlReview")
    args(providers.gradleProperty("exportReviewDir").orElse(layout.buildDirectory.dir("export-review").map { it.asFile.absolutePath }).get())
}

tasks.register<JavaExec>("walkExampleReview") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ru.hartbro.core.WalkExampleReview")
    args(providers.gradleProperty("walkReviewDir").orElse(layout.buildDirectory.dir("walk-review").map { it.asFile.absolutePath }).get())
}
