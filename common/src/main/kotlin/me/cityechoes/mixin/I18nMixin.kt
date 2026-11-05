package me.cityechoes.mixin

import me.cityechoes.ui.DangoSettingsZh

import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * 给 dangomusic 的中文字典补上都市回响的词条。
 *
 * 设置页画分组标题、行名、说明、下拉项时都会先过 `I18n.text()`，查不到就原样返回，
 * 所以不补这张表的话，英文词条会直接漏到中文界面里。
 *
 * 注入 `getChinese()` 的返回处，把原表和我们的词条合并后返回；合并结果按原表实例缓存，
 * `I18n.text()` 每帧调用几百次也不会反复拷贝（见 [DangoSettingsZh.merge]）。
 */
@Mixin(targets = ["me.sorihiko.dango.i18n.I18n"])
abstract class I18nMixin {

    @Inject(method = ["getChinese"], at = [At("RETURN")], cancellable = true, require = 0)
    private fun cityechoesChinese(cir: CallbackInfoReturnable<Map<String, String>>) {
        val base = cir.returnValue ?: return
        val merged = DangoSettingsZh.merge(base)
        if (merged !== base) cir.returnValue = merged
    }
}
