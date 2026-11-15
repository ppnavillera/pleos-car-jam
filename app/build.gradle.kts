plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// release 서명 정보(계획서 3.1). 저장소 밖 파일에서 읽는다. 찾는 순서:
//  1) 환경변수 PJAM_KEYSTORE_PROPERTIES(파일 경로)  2) 저장소 루트 keystore.properties(.gitignore)  3) ~/.pjam-signing/keystore.properties
// 파일 내용: storeFile, storePassword, keyAlias, keyPassword. 키 생성 명령은 README에 있다. 서명 키·비밀번호는 저장소에 넣지 않는다.
val keystorePropsFile: File? = listOfNotNull(
    providers.environmentVariable("PJAM_KEYSTORE_PROPERTIES").orNull?.let { File(it) },
    rootProject.file("keystore.properties"),
    File(System.getProperty("user.home"), ".pjam-signing/keystore.properties"),
).firstOrNull { it.isFile }
val keystoreProps: Map<String, String> = keystorePropsFile
    ?.let { providers.fileContents(layout.file(providers.provider { it })).asText.orNull }
    ?.lineSequence()?.map { it.trim() }?.filter { it.contains("=") && !it.startsWith("#") }
    ?.associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
    ?: emptyMap()
val hasReleaseKeystore = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !keystoreProps[it].isNullOrBlank() }

android {
    namespace = "app.pjam.studio"
    compileSdk = 36

    // Vehicle SDK / AAOS Car API (android.car) -- 계획서 3.1
    useLibrary("android.car")

    defaultConfig {
        applicationId = "app.pjam.studio"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-prototype"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = File(keystoreProps.getValue("storeFile"))
                storePassword = keystoreProps.getValue("storePassword")
                keyAlias = keystoreProps.getValue("keyAlias")
                keyPassword = keystoreProps.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 계획서 T2-10: 기본은 minify 끔. R8을 켜면 Vehicle SDK 클래스(리스너·람다 인터페이스)를 유지해야 하므로 켜지 않는다.
            isMinifyEnabled = false
            if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        // 사운드팩 WAV는 저장소 루트 assets/ 에 둔다 (tools/soundgen 결과)
        getByName("main").assets.srcDir(rootProject.file("assets"))
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pleos.vehicle)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// 서명 정보가 없으면 release 패키징만 명확한 오류로 멈춘다. debug 빌드·lint·단위 테스트에는 영향이 없다.
tasks.matching { it.name == "packageRelease" || it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    val ok = hasReleaseKeystore
    doFirst {
        if (!ok) throw GradleException(
            "release 서명 정보가 없습니다. PJAM_KEYSTORE_PROPERTIES 환경변수, 저장소 루트 keystore.properties, " +
                "또는 ~/.pjam-signing/keystore.properties 중 하나에 storeFile/storePassword/keyAlias/keyPassword를 두세요. (README 참고)",
        )
    }
}
