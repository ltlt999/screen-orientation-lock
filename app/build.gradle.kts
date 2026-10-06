import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

/**
 * 正式签名配置从仓库外的 keystore.properties 读取。
 *
 * 该文件不入库，所以别人 clone 下来不会自动拿到你的签名密钥。
 * 没有这个文件时 [releaseSigningConfig] 为 null，release 包会退化成未签名，
 * 而不是让构建失败——开源项目应当「clone 即可构建」。
 */
val releaseSigningConfig: Pair<File, Properties>? = run {
    val propsFile = rootProject.file("keystore.properties")
    if (!propsFile.exists()) return@run null
    val props = Properties().apply { propsFile.inputStream().use { load(it) } }
    rootProject.file(props.getProperty("storeFile")) to props
}

android {
    namespace = "com.orientlock"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.orientlock"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            releaseSigningConfig?.let { (store, props) ->
                storeFile = store
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 只有拿到密钥时才套用；没有就保持未签名，构建不失败
            if (releaseSigningConfig != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
