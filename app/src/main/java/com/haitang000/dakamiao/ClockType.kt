package com.haitang000.dakamiao

/** 打卡类型：上班 / 下班。决定使用哪一组导航步骤。 */
enum class ClockType(val label: String) {
    ON("上班"),
    OFF("下班");

    companion object {
        fun fromName(name: String?): ClockType =
            entries.firstOrNull { it.name == name } ?: ON
    }
}
