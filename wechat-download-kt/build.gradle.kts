import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.20"
    application
    id("io.ktor.plugin") version "2.3.7"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.20"
}

group = "com.wamp.download"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    val ktorVersion = "2.3.7"

    // Ktor server
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-gson:$ktorVersion")
    implementation("io.ktor:ktor-server-call-logging:$ktorVersion")
    implementation("io.ktor:ktor-server-cors:$ktorVersion")

    // HTTP 客户端（与 Worker 一致）
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

    // JSON（与 Worker 一致）
    implementation("com.google.code.gson:gson:2.10.1")

    // SQLite + Exposed ORM
    implementation("org.xerial:sqlite-jdbc:3.44.1.0")
    implementation("org.jetbrains.exposed:exposed-core:0.46.0")
    implementation("org.jetbrains.exposed:exposed-dao:0.46.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.46.0")
    implementation("org.jetbrains.exposed:exposed-kotlin-datetime:0.46.0")

    // YAML 配置解析（与 Worker 一致）
    implementation("org.yaml:snakeyaml:2.2")

    // 日志（与 Worker 一致）
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Coroutines（与 Worker 一致）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // RabbitMQ Java client（与 Worker 一致）
    implementation("com.rabbitmq:amqp-client:5.18.0")

    // 二维码生成（微信读书扫码登录推送）
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.google.zxing:javase:3.5.2")

    // 测试
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:1.9.20")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.1")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "17"
}

application {
    mainClass.set("com.wamp.download.MainKt")
}
