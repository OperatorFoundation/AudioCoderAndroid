plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    id("maven-publish")
}

android {
    namespace = "org.operatorfoundation.audiocoder"
    compileSdk = 35

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "x86", "arm64-v8a", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                targets("QuietScream")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    // JitPack publishing
    publishing {
        singleVariant("release") {
            // Include sources and javadoc for better developer experience
            withSourcesJar()
            withJavadocJar()
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

// JitPack publishing configuration
afterEvaluate {
    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])

                // Set the Maven coordinates
                groupId = "com.github.OperatorFoundation"
                artifactId = "AudioCoderAndroid"
                version = "1.0-SNAPSHOT" // This will be overridden by JitPack

                // Add metadata for better Maven repository experience
                pom {
                    name.set("AudioCoder Android Library")
                    description.set("Android library for audio coding functionality with WSPR support")
                    url.set("https://github.com/OperatorFoundation/AudioCoderAndroid")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }

                    developers {
                        developer {
                            id.set("OperatorFoundation")
                            name.set("Operator Foundation")
                            url.set("https://github.com/OperatorFoundation")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/OperatorFoundation/AudioCoderAndroid.git")
                        developerConnection.set("scm:git:ssh://github.com:OperatorFoundation/AudioCoderAndroid.git")
                        url.set("https://github.com/OperatorFoundation/AudioCoderAndroid/tree/main")
                    }
                }
            }
        }
    }
}