import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.task.RemapJarTask
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

import java.util.Properties

/*
 * DangoMusic City Echoes —— 独立 addon 构建。
 * 仅依赖 dangomusic（modCompileOnly，不打包进 jar），用两个 Mixin 把都市回响模式注入 dangomusic。
 * 共用 Kotlin 源码在 common/src/main/kotlin；每个 loader 子项目提供自己的 mod 元数据 + mixins.json。
 */
plugins {
    id("dev.architectury.loom") version "1.13-SNAPSHOT" apply false
    kotlin("jvm") version "2.2.21" apply false
    // apply false 只是把 Shadow 的任务类型（ShadowJar）暴露给根脚本的 classpath；
    // 真正的 apply 在 subprojects {} 里对每个子项目执行。
    id("com.gradleup.shadow") version "9.2.2" apply false
}

val modVersion: String = Properties().apply {
    rootDir.resolve("version.properties").inputStream().use { load(it) }
}.getProperty("version")

subprojects {
    repositories {
        maven("https://bmclapi2.bangbang93.com/maven/") { name = "BMCLAPI" }
        maven(rootProject.file("localrepo")) { name = "LocalRepo" }
        mavenCentral()
        maven("https://maven.neoforged.net/releases/") { name = "NeoForge" }
    }

    apply(plugin = "dev.architectury.loom")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.gradleup.shadow")

    val loader = property("loom.platform") as String
    val mcVersion = property("minecraft_version") as String
    val mcRange = property("minecraft_range") as String
    val javaVersion = (property("java_version") as String).toInt()

    group = "me.cityechoes"
    version = modVersion
    extensions.configure<BasePluginExtension> { archivesName.set("DangoMusic-CityEchoes") }

    // 共用 Kotlin 源码（与 loader 无关：只用到 dangomusic 公开 API + Mixin）
    val shared = rootProject.file("common/src/main/kotlin")
    extensions.configure<KotlinJvmProjectExtension> {
        sourceSets.getByName("main").kotlin.srcDir(shared)
        compilerOptions { jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString())) }
    }
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.toVersion(javaVersion)
        targetCompatibility = JavaVersion.toVersion(javaVersion)
    }
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(javaVersion)
        options.encoding = "UTF-8"
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions { jvmTarget.set(JvmTarget.fromTarget(javaVersion.toString())) }
    }

    // 要打进 jar 的依赖：只有 Kotlin 运行库（dangomusic 已自带，它重定位到 me.sorihiko.dango.shadow.kotlin，互不冲突）
    val shade = configurations.create("shade") {
        isCanBeResolved = true
        isCanBeConsumed = false
    }
    configurations.getByName("implementation").extendsFrom(shade)

    val loom = extensions.getByType<LoomGradleExtensionAPI>()
    val dangomusicJar = "DangoMusic-1.7.3+$mcVersion-$loader.jar"

    dependencies {
        "minecraft"("com.mojang:minecraft:$mcVersion")
        "mappings"(loom.officialMojangMappings())
        when (loader) {
            "forge" -> "forge"("net.minecraftforge:forge:${property("forge_version")}")
            "neoforge" -> "neoForge"("net.neoforged:neoforge:${property("neoforge_version")}")
            else -> error("Unknown loader: $loader")
        }
        // dangomusic 作为编译期依赖（提供 Mixin 目标类 / 公开 API），运行时由用户同时安装，不打包
        "modCompileOnly"(files(rootProject.file("libs/$dangomusicJar")))
        // Mixin 注解（运行时由 Forge/NeoForge 自带的 Mixin 提供，只编译期需要）
        "compileOnly"(files(rootProject.file("libs/mixin-patched-0.8.5.12.jar")))
        // Mixin 注解处理器：Loom 用它生成 refmap（我们的 mixin 目标都是 dangomusic 未混淆的类，refmap 等价于恒等映射）
        "annotationProcessor"(files(rootProject.file("libs/mixin-patched-0.8.5.12.jar")))
        "shade"(kotlin("stdlib"))
    }

    tasks.named<ProcessResources>("processResources") {
        val props = mapOf(
            "version" to modVersion,
            "minecraft_range" to mcRange,
            "java_version" to javaVersion.toString(),
            "target" to "$mcVersion-$loader",
        )
        inputs.properties(props)
        filesMatching(listOf("META-INF/mods.toml", "META-INF/neoforge.mods.toml", "dangomusic_cityechoes.mixins.json")) {
            expand(props)
        }
    }

    // Mixin 配置的声明方式在两个加载器上不同：
    //  - Forge 1.20.1 的 mods.toml 不认 [[mixins]]（ModFileParser 里没有相关逻辑），只认 jar manifest 的 MixinConfigs；
    //  - NeoForge 21.1 认 neoforge.mods.toml 的 [[mixins]]（见 versions/1.21.1-neoforge 的元数据）。
    // 所以只在 forge 目标写 manifest，避免 NeoForge 上同一个 config 被声明两次。
    val mixinConfig = "dangomusic_cityechoes.mixins.json"
    if (loader == "forge") {
        tasks.named<Jar>("jar") {
            manifest { attributes("MixinConfigs" to mixinConfig) }
        }
    }

    val shadowJar = tasks.named<ShadowJar>("shadowJar") {
        configurations.set(listOf(shade))
        archiveClassifier.set("dev-shadow")
        if (loader == "forge") manifest { attributes("MixinConfigs" to mixinConfig) }
        // 把 kotlin 运行库重定位到 addon 自己的命名空间（和 dangomusic 的做法一致）：
        //  - 1.20.1 实例没有 kotlinforforge，addon 必须自带 kotlin 才能跑；
        //  - 1.21.1 实例装了 kotlinforforge，裸打包 kotlin 会和它撞包（NeoForge 模块层不允许重复包）。
        // 重定位后 addon 完全自包含，两种环境都不冲突。
        relocate("kotlin", "me.cityechoes.shadow.kotlin")
        exclude("module-info.class", "META-INF/versions/**", "META-INF/*.kotlin_module")
        // Shadow 9 默认加 Multi-Release，但 META-INF/versions 已去掉，留着会让 Forge 1.20.1 加载崩溃
        addMultiReleaseAttribute.set(false)
        // GPL-3.0 要求分发二进制时附带许可文本，所以直接打进 jar
        from(rootProject.file("LICENSE")) { into("META-INF") }
        from(rootProject.file("THIRD_PARTY_NOTICES.md")) { into("META-INF") }
    }

    tasks.named<RemapJarTask>("remapJar") {
        inputFile.set(shadowJar.flatMap { it.archiveFile })
        archiveVersion.set("$modVersion+$mcVersion")
        archiveClassifier.set(loader) // DangoMusic-CityEchoes-1.0.0+1.20.1-forge.jar
    }

    tasks.named("build") { dependsOn("remapJar") }
}
