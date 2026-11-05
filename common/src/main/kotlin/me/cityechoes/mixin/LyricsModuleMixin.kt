package me.cityechoes.mixin

import me.cityechoes.mods.CityEchoesModule
import me.sorihiko.dango.events.HudRenderEvent
import me.sorihiko.dango.events.WorldRenderEndEvent
import me.sorihiko.dango.module.impls.music.LyricsModule
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

/**
 * 都市回响启用时，取消原版 LyricsModule 的 3D 歌词与左上角 Now playing 渲染，
 * 改由本 addon 的 CityEchoesModule 来画，避免两份歌词重叠。
 * 模块关闭时（[CityEchoesModule.isActive] 为 false）不动原版，歌词照常显示。
 */
@Mixin(LyricsModule::class)
class LyricsModuleMixin {

    @Inject(method = ["render"], at = [At("HEAD")], cancellable = true)
    private fun onRender(e: WorldRenderEndEvent, ci: CallbackInfo) {
        if (CityEchoesModule.isActive()) ci.cancel()
    }

    @Inject(method = ["renderHud"], at = [At("HEAD")], cancellable = true)
    private fun onRenderHud(e: HudRenderEvent, ci: CallbackInfo) {
        if (CityEchoesModule.isActive()) ci.cancel()
    }
}
