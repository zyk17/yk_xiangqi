import java.util.Collections

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.yk.xiangqi"
    compileSdk = 36
    lint {
        // 本项目仅面向 Android 9 本地侧载，不参加 Google Play 的目标 API 要求。
        disable += "ExpiredTargetSdkVersion"
    }
    defaultConfig {
        applicationId = "com.yk.xiangqi"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        getByName("release") {
            // 当前仅本地侧载；使用 Android 默认 debug keystore 生成可安装的 release APK。
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
        jniLibs.useLegacyPackaging = true
    }
}


tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    testImplementation("junit:junit:4.13.2")
}

val perftCategory = "com.yk.xiangqi.core.PerftTest"

tasks.withType<Test>().configureEach {
    if (name != "perftTest") {
        useJUnit {
            excludeCategories(perftCategory)
        }
    }
}

gradle.taskGraph.whenReady {
    tasks.withType<Test>().forEach { test ->
        // IDE 和命令行精确指定 PositionPerftTest 时，显式请求应优先于默认排除规则。
        val commandLinePatterns = (test.filter as? org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter)
            ?.commandLineIncludePatterns.orEmpty()
        if (test.name != "perftTest" && commandLinePatterns.any { it.contains("PositionPerftTest") }) {
            (test.options as org.gradle.api.tasks.testing.junit.JUnitOptions).excludeCategories = Collections.emptySet()
        }
    }
}

afterEvaluate {
    val debugUnitTest = tasks.named<Test>("testDebugUnitTest")

    tasks.register<Test>("perftTest") {
        group = "verification"
        description = "运行完整象棋 perft 基准测试。"
        dependsOn("compileDebugUnitTestSources")
        testClassesDirs = debugUnitTest.get().testClassesDirs
        classpath = debugUnitTest.get().classpath
        useJUnit {
            includeCategories(perftCategory)
        }
    }
}
