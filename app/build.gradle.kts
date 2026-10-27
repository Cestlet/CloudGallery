import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
val localConfig = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun literal(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val baiduAppKey = providers.environmentVariable("CLOUDGALLERY_BAIDU_APP_KEY")
    .orElse(localConfig.getProperty("baidu.appKey", "")).get().trim()
val baiduSecret = providers.environmentVariable("CLOUDGALLERY_BAIDU_SECRET")
    .orElse(localConfig.getProperty("baidu.secret", "")).get().trim()

tasks.register("verifyBaiduConfig") {
    group = "verification"
    description = "Checks real Baidu application configuration without printing credentials."
    doLast {
        check(baiduAppKey.isNotEmpty() && baiduSecret.isNotEmpty()) {
            "Missing Baidu App Key or Secret Key. Set baidu.appKey and baidu.secret in local.properties, or CLOUDGALLERY_BAIDU_APP_KEY and CLOUDGALLERY_BAIDU_SECRET environment variables."
        }
        check(baiduAppKey != "fixture-key" && baiduSecret != "fixture-secret") {
            "Mock credentials cannot be used for real Baidu authorization."
        }
        check((baiduAppKey + baiduSecret).none { it.isWhitespace() || it.isISOControl() }) {
            "Baidu credentials contain unexpected characters. Copy the application App Key and Secret Key from the developer console."
        }
        logger.lifecycle("Baidu application configuration present; real authorization still requires browser consent and API validation.")
    }
}
android {
    namespace = "com.cloudgallery.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.cloudgallery.app"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "com.cloudgallery.app.XhsActionProbe"
        versionCode = 24
        versionName = "0.6.12"
        buildConfigField("String", "BAIDU_APP_KEY", literal(baiduAppKey))
        buildConfigField("String", "BAIDU_SECRET", literal(baiduSecret))
        buildConfigField("String", "BAIDU_API", literal("https://pan.baidu.com/"))
        buildConfigField("String", "BAIDU_OAUTH", literal("https://openapi.baidu.com/"))
        buildConfigField("boolean", "MOCK_BAIDU", "false")
    }
    flavorDimensions += "environment"
    productFlavors {
        create("real") { dimension = "environment" }
        create("mock") {
            dimension = "environment"
            applicationIdSuffix = ".mock"
            versionNameSuffix = "-mock"
            resValue("string", "app_name", "PixNexus Gallary 模拟")
            buildConfigField("boolean", "MOCK_BAIDU", "true")
            buildConfigField("String", "BAIDU_APP_KEY", literal("fixture-key"))
            buildConfigField("String", "BAIDU_SECRET", literal("fixture-secret"))
            buildConfigField("String", "BAIDU_API", literal("http://127.0.0.1:5246/"))
            buildConfigField("String", "BAIDU_OAUTH", literal("http://127.0.0.1:5246/"))
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.tracing:tracing:1.2.0")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.json:json:20240303")
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }

tasks.register("exportRuntimeArtifacts") {
    group = "verification"
    description = "Exports resolved runtime libraries for the bundled third-party license inventory."
    doLast {
        val destination = layout.buildDirectory.file("tmp/runtime-artifacts.tsv").get().asFile
        destination.parentFile.mkdirs()
        destination.writeText(configurations.getByName("realDebugRuntimeClasspath").resolvedConfiguration.resolvedArtifacts
            .sortedBy { it.moduleVersion.id.toString() }.joinToString("\n") {
                "${it.moduleVersion.id}\t${it.file.absolutePath}"
            })
        logger.lifecycle("Runtime library inventory exported.")
    }
}
