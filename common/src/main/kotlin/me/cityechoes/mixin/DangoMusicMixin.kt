package me.cityechoes.mixin

import me.cityechoes.mods.CityEchoesModule
import me.sorihiko.dango.DangoMusic
import me.sorihiko.dango.module.ModuleManager
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.Shadow
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * 在 dangomusic 初始化时，把都市回响模块注册进它的模块系统。
 *
 * 注入点选在 [DangoMusic.init] 里 `moduleManager.load()` 调用之前：
 * load() 会把当时 modules 表里的所有模块一次性快照后再初始化，所以必须在它之前注册，
 * 模块才会被 init 并接到事件总线；之后由 dangomusic 的 ModuleManager 负责派发事件。
 *
 * 不取 DangoMusic 单例：注入进去的 `this` 就是 DangoMusic 实例，
 * 直接用 @Shadow 拿到它 public 的 getModuleManager() 即可。
 */
@Mixin(DangoMusic::class)
abstract class DangoMusicMixin {

    @Shadow
    abstract fun getModuleManager(): ModuleManager

    @Inject(
        method = ["init"],
        at = [At(
            value = "INVOKE",
            target = "Lme/sorihiko/dango/module/ModuleManager;load()V",
            shift = At.Shift.BEFORE,
        )],
    )
    private fun onInit(modules: List<*>, ci: CallbackInfo) {
        val mod = CityEchoesModule()
        getModuleManager().register(mod)
        // 让 LyricsModuleMixin 能查到本模块是否已启用，从而接管原版渲染
        CityEchoesModule.INSTANCE = mod
    }
}
