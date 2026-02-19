import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform

plugins {
    id("fabric-loom") version "1.9.2"
    id("maven-publish")
}

val minecraftVersion = "1.20.1"
val fabricLoaderVersion = "0.17.2"
val fabricApiVersion = "0.92.7+1.20.1"
val modVersion = "0.1.0-alpha"
val lwjglVersion = "3.3.2"

base {
    archivesName = "vulkanium"
    group = "net.vulkanium"
    version = modVersion
}

repositories {
    maven("https://maven.fabricmc.net/") { name = "Fabric" }
    mavenCentral()
}

loom {
    accessWidenerPath = file("src/main/resources/vulkanium.accesswidener")

    mixin {
        useLegacyMixinAp = false
    }

    runs {
        named("client") {
            isIdeConfigGenerated = true
            if (DefaultNativePlatform.getCurrentOperatingSystem().isLinux) {
                // Optional: enable RenderDoc for Vulkan debugging
                // environmentVariable("LD_PRELOAD", "/usr/lib/librenderdoc.so")
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")

    // Fabric API modules
    modImplementation(fabricApi.module("fabric-api-base", fabricApiVersion))
    modImplementation(fabricApi.module("fabric-resource-loader-v0", fabricApiVersion))
    modImplementation(fabricApi.module("fabric-rendering-fluids-v1", fabricApiVersion))
    modImplementation(fabricApi.module("fabric-rendering-v1", fabricApiVersion))

    // LWJGL Vulkan (already in Minecraft, but declare for compile)
    implementation("org.lwjgl:lwjgl-vulkan:$lwjglVersion")

    // VMA (Vulkan Memory Allocator)
    include(implementation("org.lwjgl:lwjgl-vma:$lwjglVersion")!!)
    include(runtimeOnly("org.lwjgl:lwjgl-vma:$lwjglVersion:natives-windows")!!)
    include(runtimeOnly("org.lwjgl:lwjgl-vma:$lwjglVersion:natives-linux")!!)
    include(runtimeOnly("org.lwjgl:lwjgl-vma:$lwjglVersion:natives-macos")!!)
    include(runtimeOnly("org.lwjgl:lwjgl-vma:$lwjglVersion:natives-macos-arm64")!!)

    // Shaderc (GLSL → SPIR-V compiler)
    include(implementation("org.lwjgl:lwjgl-shaderc:$lwjglVersion")!!)
    include(runtimeOnly("org.lwjgl:lwjgl-shaderc:$lwjglVersion:natives-windows")!!)
    include(runtimeOnly("org.lwjgl:lwjgl-shaderc:$lwjglVersion:natives-linux")!!)
    include(runtimeOnly("org.lwjgl:lwjgl-shaderc:$lwjglVersion:natives-macos")!!)
    include(runtimeOnly("org.lwjgl:lwjgl-shaderc:$lwjglVersion:natives-macos-arm64")!!)

    // MoltenVK for macOS
    include(runtimeOnly("org.lwjgl:lwjgl-vulkan:$lwjglVersion:natives-macos")!!)
    include(runtimeOnly("org.lwjgl:lwjgl-vulkan:$lwjglVersion:natives-macos-arm64")!!)

    // ANTLR4 + GLSL Transformer (for shader pack GLSL transformation)
    include(implementation("org.antlr:antlr4-runtime:4.13.1")!!)
    include(implementation("io.github.douira:glsl-transformer:2.0.1")!!)
    include(implementation("org.anarres:jcpp:1.4.14")!!)
}

tasks.withType<JavaCompile> {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.jar {
    from("LICENSE") {
        rename { "${it}_vulkanium" }
    }
}

tasks.processResources {
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}
