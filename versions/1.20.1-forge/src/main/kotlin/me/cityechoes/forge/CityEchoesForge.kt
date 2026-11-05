package me.cityechoes.forge

import net.minecraftforge.fml.common.Mod

/** 本 addon 的 mod id */
const val MOD_ID = "dangomusic_cityechoes"

/**
 * Forge 版本的 mod 入口。
 *
 * Forge 1.20.1 的 mods.toml 不能指定入口 class，FML 只能扫描 jar 里的 @Mod 注解来构造 mod 容器；
 * 没有这个类，加载期会报 "constructed 0 mods ... but had 1 mods specified" 而直接崩游戏。
 *
 * 除了占位之外这里什么都不注册：设置界面已经完全并进 dangomusic 自己的设置页（第三个 tab），
 * 由 [me.cityechoes.mixin.SettingsPageMixin] 注入；曾经那个 K 键独立页面已移除。
 * 歌词渲染的注入仍在 [me.cityechoes.mixin.DangoMusicMixin] 里完成。
 */
@Mod(MOD_ID)
class CityEchoesForge
