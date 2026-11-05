// init.gradle.kts —— 把 Loom 注册的 Forge / Maven Central 仓库 URL 改写为国内镜像 BMCLAPI。
// 作用：Loom 用 exclusiveContent 把 forge/mojang 包锁死在官方源，普通 repositories 加镜像会被绕过；
//       本脚本在仓库被注册后改写其 URL，保留原 exclusiveContent 槽位，仅换镜像源。
// 用法（构建时加 --init-script 本文件）：
//   gradle :1.20.1-forge:clean :1.20.1-forge:build --init-script init.gradle.kts
//
// 注意：BMCLAPI 实测镜像 forge(maven.minecraftforge.net)、Maven Central(repo.maven.apache.org)
//       以及 neoforged(maven.neoforged.net)；不镜像 fabricmc / architectury / com.mojang，故其余保持原样。
//       neoforged 官方源在本环境直连(000)/代理(502)均不可达，必须改写为镜像，否则 NeoForge 目标构建失败。

// 把 architectury 自有仓库注入 pluginManagement，供 Loom 插件的传递依赖
// （如 dev.architectury:refmap-remapper，fabricmc 镜像缺失/不稳）解析。
beforeSettings {
    pluginManagement {
        repositories {
            maven("https://maven.architectury.dev/") { name = "Architectury" }
        }
    }
}

allprojects {
    repositories {
        maven("https://maven.architectury.dev/") { name = "Architectury" }
    }
    repositories.all {
        if (this is MavenArtifactRepository) {
            val original = this.url.toString()
            val mapped = when {
                original.startsWith("https://maven.minecraftforge.net") ->
                    "https://bmclapi2.bangbang93.com/maven" +
                        original.removePrefix("https://maven.minecraftforge.net")
                original.startsWith("https://repo.maven.apache.org/maven2") ->
                    "https://bmclapi2.bangbang93.com/maven" +
                        original.removePrefix("https://repo.maven.apache.org/maven2")
                original.startsWith("https://maven.neoforged.net") ->
                    "https://bmclapi2.bangbang93.com/maven" +
                        original.removePrefix("https://maven.neoforged.net").removePrefix("/releases")
                else -> original
            }
            if (mapped != original) {
                this.setUrl(mapped)
            }
        }
    }
}
