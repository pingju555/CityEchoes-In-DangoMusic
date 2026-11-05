package me.cityechoes.neoforge

import net.neoforged.fml.common.Mod

/** 本 addon 的 mod id */
const val MOD_ID = "dangomusic_cityechoes"

/**
 * NeoForge 版本的 mod 入口。
 *
 * 与 Forge 同理：neoforge.mods.toml 不指定入口 class，FML 靠扫描 @Mod 注解构造 mod 容器，
 * 缺少这个类会在加载期报 "constructed 0 mods" 并崩溃。
 *
 * 除了占位之外这里什么都不注册：设置界面已经完全并进 dangomusic 自己的设置页（第三个 tab），
 * 由 [me.cityechoes.mixin.SettingsPageMixin] 注入；曾经那个 K 键独立页面已移除。
 * 歌词渲染的注入仍在 [me.cityechoes.mixin.DangoMusicMixin] 里完成。
 */
@Mod(MOD_ID)
class CityEchoesNeoForge
