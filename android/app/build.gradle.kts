plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Configuração do projeto
// ---------------------------------------------------------------------------

// Proxy seguro da IA (Cloudflare Worker). A chave Gemini fica no servidor, nunca no APK.
// Ordem de precedência: -PclaudgameAiBackendUrl=...  >  variável CLAUDGAME_AI_BACKEND_URL  >  Worker padrão.
val defaultAiBackendUrl = "https://wandering-haze-ce04.lucasmoraes-m2.workers.dev"
val aiBackendUrl: String = providers.gradleProperty("claudgameAiBackendUrl")
    .orElse(providers.environmentVariable("CLAUDGAME_AI_BACKEND_URL"))
    .orElse(defaultAiBackendUrl)
    .get()
    .trim()
    .trimEnd('/')
    .ifBlank { defaultAiBackendUrl }

// OCR local com ML Kit (padrão). Use -PclaudgameOcr=none para compilar sem ML Kit,
// por exemplo em ambientes sem acesso ao Maven do Google. A captura e a IA online continuam funcionando.
val enableMlKit: Boolean = providers.gradleProperty("claudgameOcr").orElse("mlkit").get() != "none"

// Keystore estável do projeto (versionada de propósito): todo build do ClaudGame usa a mesma
// assinatura, então um APK novo sempre instala por cima do anterior. É uma chave de uso
// pessoal/sideload, NÃO serve para publicação em loja.
val projectKeystore = rootProject.file("claudgame-debug.keystore")

android {
    namespace = "com.lucasmoraes.claudgame"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lucasmoraes.claudgame"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.9.0"
        buildConfigField("String", "AI_BACKEND_URL", "\"$aiBackendUrl\"")
    }

    buildFeatures { buildConfig = true }

    signingConfigs {
        if (projectKeystore.exists()) {
            create("claudGame") {
                storeFile = projectKeystore
                storePassword = "claudgame123"
                keyAlias = "claudgame"
                keyPassword = "claudgame123"
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("claudGame")?.let { signingConfig = it }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Release assinado com a mesma chave do projeto para o APK ser instalável.
            signingConfigs.findByName("claudGame")?.let { signingConfig = it }
        }
    }

    sourceSets {
        getByName("main") {
            // Seleciona, em tempo de build, a implementação de OCR (ML Kit ou nenhuma).
            java.srcDir(if (enableMlKit) "src/ocrMlKit/java" else "src/ocrNone/java")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        // O build de release não deve falhar por avisos de lint; rode ./gradlew lint para o relatório.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// O app usa somente APIs da plataforma Android (Activity/Service/WindowManager...), por isso
// AppCompat, Material, core-ktx e activity-ktx foram removidos: eram declarados, mas não
// agregavam nada além de risco de incompatibilidade de tema/versão.
dependencies {
    if (enableMlKit) {
        implementation("com.google.mlkit:text-recognition:16.0.1")
    }
    testImplementation("junit:junit:4.13.2")
}