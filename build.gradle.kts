// Root build: declare shared plugin versions once; each module applies what it needs.
plugins {
    kotlin("jvm") version "2.4.0" apply false
    kotlin("plugin.serialization") version "2.4.0" apply false
    id("com.gradleup.shadow") version "9.4.2" apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}
