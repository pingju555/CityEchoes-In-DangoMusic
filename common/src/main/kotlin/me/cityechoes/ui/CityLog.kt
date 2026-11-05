package me.cityechoes.ui

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * 统一日志出口。
 *
 * 存在的理由有两个：
 * 1. Forge 1.20.1 不会把 `System.err` / `System.out` 收进 `latest.log`，
 *    所以诊断信息必须走 slf4j 才看得见。
 * 2. Mixin 类里不能声明字段或 companion object —— 它们会被编译成独立嵌套类，
 *    而注入时只有目标类需要的成员会被合并，其余引用会变成 NoClassDefFoundError。
 *    所以 mixin 里一律通过这个对象打印。
 */
internal object CityLog {
    val log: Logger = LoggerFactory.getLogger("CityEchoes")
}
