plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ar.despertador.evangelio"
    compileSdk = 34

    defaultConfig {
        applicationId = "ar.despertador.evangelio"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // Firma: clave privada propia de Lazarus. El archivo lazarus.p12 está cifrado con AES-256 y
    // su contraseña solo existe como secreto de GitHub (LAZARUS_KEY_PASSWORD). Sin la contraseña
    // la clave no sirve, así que nadie puede firmar una versión falsa que Android acepte como Lazarus.
    val keyPassword = System.getenv("LAZARUS_KEY_PASSWORD")
    signingConfigs {
        if (!keyPassword.isNullOrEmpty()) {
            create("lazarus") {
                storeFile = file("lazarus.p12")
                storeType = "PKCS12"
                storePassword = keyPassword
                keyAlias = "lazarus"
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Sin la contraseña (por ejemplo, compilando en una PC) usa la firma de prueba de Android.
            if (!keyPassword.isNullOrEmpty()) signingConfig = signingConfigs.getByName("lazarus")
        }
        getByName("release") {
            isMinifyEnabled = false
            // Misma clave que la de debug: las versiones nuevas se instalan encima de las anteriores.
            if (!keyPassword.isNullOrEmpty()) signingConfig = signingConfigs.getByName("lazarus")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
    }
}
