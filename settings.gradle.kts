pluginManagement {
    repositories {
        // 本地依赖仓库：可选，存在时才注册（它不进版本库，所以 clone 后通常没有）
        val localRepo = File(rootDir, "localrepo")
        if (localRepo.isDirectory) {
            maven { url = localRepo.toURI(); name = "LocalRepo" }
        }
        maven("https://maven.aliyun.com/repository/public") { name = "Aliyun" }
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.architectury.dev/") { name = "Architectury" }
        maven("https://maven.minecraftforge.net/") { name = "Forge" }
        maven("https://maven.neoforged.net/releases/") { name = "NeoForge" }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "CityEchoes-In-DangoMusic"

// 每个"Minecraft 版本 + 加载器"一个子项目，配置在 versions/<名字>/gradle.properties
val targets = listOf("1.20.1-forge", "1.21.1-neoforge")
targets.forEach {
    include(it)
    project(":$it").projectDir = file("versions/$it")
}
