plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(libs.versions.java.get().toInt())
}

application {
    applicationName = "ledger-pw"
    mainClass.set("com.ledgerpasswords.companion.cli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ledger-protocol"))
    testImplementation(libs.junit.jupiter)
}
