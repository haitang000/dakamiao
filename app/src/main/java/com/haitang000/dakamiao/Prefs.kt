package com.haitang000.dakamiao

import android.content.Context

/**
 * 全部配置读写的唯一入口，基于 SharedPreferences。
 * 关键词/步骤都用「一行一个」的纯文本存，方便用户在界面里直接编辑成自己钉钉的实际按钮文案。
 */
object Prefs {
    private const val FILE = "dakamiao_prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_WORKDAY_ONLY = "workday_only"
    private const val KEY_OCR = "ocr_enabled"
    private const val KEY_ONBOARDED = "onboarded"
    private const val KEY_ON_TIME = "on_time"
    private const val KEY_OFF_TIME = "off_time"
    private const val KEY_ON_STEPS = "on_steps"
    private const val KEY_OFF_STEPS = "off_steps"
    private const val KEY_SUCCESS = "success_keywords"
    private const val KEY_FACE = "face_keywords"
    private const val KEY_CONFIRM = "confirm_keywords"
    private const val KEY_LAUNCH_WAIT = "launch_wait_ms"
    private const val KEY_STEP_TIMEOUT = "step_timeout_ms"

    // 默认值：按最常见的钉钉「工作台 → 考勤打卡 → 上/下班打卡」路径给出，用户可自行修改。
    const val DEFAULT_ON_TIME = "09:00"
    const val DEFAULT_OFF_TIME = "18:00"
    // 路径：消息页滚动到顶 → 点顶部导航栏「打卡」进入打卡页 → 点「上/下班打卡」大按钮
    // 以 @ 开头的是动作指令（如 @滚动到顶部），其余是要点击的按钮文字
    const val DEFAULT_ON_STEPS = "@滚动到顶部\n打卡\n上班打卡"
    const val DEFAULT_OFF_STEPS = "@滚动到顶部\n打卡\n下班打卡"
    const val DEFAULT_SUCCESS = "打卡成功\n更新打卡\n已打卡\n打卡时间"
    // 只放真正会挡住打卡、必须本人完成的「人脸活体识别」；「拍照」在外勤等页面通常是可选项，不拦
    const val DEFAULT_FACE = "人脸\n刷脸\n人脸识别\n眨眼\n活体\n开始识别"
    const val DEFAULT_CONFIRM = "外勤\n外出"
    const val DEFAULT_LAUNCH_WAIT_MS = 4000L
    const val DEFAULT_STEP_TIMEOUT_MS = 15000L

    private fun sp(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = sp(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, v: Boolean) =
        sp(context).edit().putBoolean(KEY_ENABLED, v).apply()

    fun isWorkdayOnly(context: Context) = sp(context).getBoolean(KEY_WORKDAY_ONLY, true)
    fun setWorkdayOnly(context: Context, v: Boolean) =
        sp(context).edit().putBoolean(KEY_WORKDAY_ONLY, v).apply()

    fun isOcrEnabled(context: Context) = sp(context).getBoolean(KEY_OCR, true)
    fun setOcrEnabled(context: Context, v: Boolean) =
        sp(context).edit().putBoolean(KEY_OCR, v).apply()

    fun isOnboarded(context: Context) = sp(context).getBoolean(KEY_ONBOARDED, false)
    fun setOnboarded(context: Context, v: Boolean) =
        sp(context).edit().putBoolean(KEY_ONBOARDED, v).apply()

    /** 一键写入推荐的步骤与关键词。 */
    fun applyRecommendedDefaults(context: Context) {
        setStepsRaw(context, ClockType.ON, DEFAULT_ON_STEPS)
        setStepsRaw(context, ClockType.OFF, DEFAULT_OFF_STEPS)
        setSuccessRaw(context, DEFAULT_SUCCESS)
        setFaceRaw(context, DEFAULT_FACE)
        setConfirmRaw(context, DEFAULT_CONFIRM)
    }

    fun getTime(context: Context, type: ClockType): String {
        val key = if (type == ClockType.ON) KEY_ON_TIME else KEY_OFF_TIME
        val def = if (type == ClockType.ON) DEFAULT_ON_TIME else DEFAULT_OFF_TIME
        return sp(context).getString(key, def) ?: def
    }

    fun setTime(context: Context, type: ClockType, value: String) {
        val key = if (type == ClockType.ON) KEY_ON_TIME else KEY_OFF_TIME
        sp(context).edit().putString(key, value).apply()
    }

    fun getSteps(context: Context, type: ClockType): List<String> {
        val key = if (type == ClockType.ON) KEY_ON_STEPS else KEY_OFF_STEPS
        val def = if (type == ClockType.ON) DEFAULT_ON_STEPS else DEFAULT_OFF_STEPS
        return parseLines(sp(context).getString(key, def) ?: def)
    }

    fun getStepsRaw(context: Context, type: ClockType): String {
        val key = if (type == ClockType.ON) KEY_ON_STEPS else KEY_OFF_STEPS
        val def = if (type == ClockType.ON) DEFAULT_ON_STEPS else DEFAULT_OFF_STEPS
        return sp(context).getString(key, def) ?: def
    }

    fun setStepsRaw(context: Context, type: ClockType, raw: String) {
        val key = if (type == ClockType.ON) KEY_ON_STEPS else KEY_OFF_STEPS
        sp(context).edit().putString(key, raw).apply()
    }

    fun getSuccessKeywords(context: Context) =
        parseLines(sp(context).getString(KEY_SUCCESS, DEFAULT_SUCCESS) ?: DEFAULT_SUCCESS)

    fun getSuccessRaw(context: Context) =
        sp(context).getString(KEY_SUCCESS, DEFAULT_SUCCESS) ?: DEFAULT_SUCCESS

    fun setSuccessRaw(context: Context, raw: String) =
        sp(context).edit().putString(KEY_SUCCESS, raw).apply()

    fun getFaceKeywords(context: Context) =
        parseLines(sp(context).getString(KEY_FACE, DEFAULT_FACE) ?: DEFAULT_FACE)

    fun getFaceRaw(context: Context) =
        sp(context).getString(KEY_FACE, DEFAULT_FACE) ?: DEFAULT_FACE

    fun setFaceRaw(context: Context, raw: String) =
        sp(context).edit().putString(KEY_FACE, raw).apply()

    fun getConfirmKeywords(context: Context) =
        parseLines(sp(context).getString(KEY_CONFIRM, DEFAULT_CONFIRM) ?: DEFAULT_CONFIRM)

    fun getConfirmRaw(context: Context) =
        sp(context).getString(KEY_CONFIRM, DEFAULT_CONFIRM) ?: DEFAULT_CONFIRM

    fun setConfirmRaw(context: Context, raw: String) =
        sp(context).edit().putString(KEY_CONFIRM, raw).apply()

    fun getLaunchWaitMs(context: Context) =
        sp(context).getLong(KEY_LAUNCH_WAIT, DEFAULT_LAUNCH_WAIT_MS)

    fun getStepTimeoutMs(context: Context) =
        sp(context).getLong(KEY_STEP_TIMEOUT, DEFAULT_STEP_TIMEOUT_MS)

    /** 把多行文本拆成关键词列表，忽略空行；支持中英文逗号分隔。 */
    private fun parseLines(raw: String): List<String> =
        raw.split('\n', '\r', ',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
