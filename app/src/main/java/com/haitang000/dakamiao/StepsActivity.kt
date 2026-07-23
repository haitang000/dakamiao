package com.haitang000.dakamiao

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.haitang000.dakamiao.databinding.ActivityStepsBinding

/** 打卡步骤与设置子页：导航步骤、各类关键词、OCR / 退出后台开关，保存/填入推荐。 */
class StepsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStepsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityStepsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        load()

        with(binding) {
            cbOcr.setOnCheckedChangeListener { _, c -> Prefs.setOcrEnabled(this@StepsActivity, c) }
            cbKill.setOnCheckedChangeListener { _, c -> Prefs.setKillBefore(this@StepsActivity, c) }

            btnResetSteps.setOnClickListener {
                etOnSteps.setText(Prefs.DEFAULT_ON_STEPS)
                etOffSteps.setText(Prefs.DEFAULT_OFF_STEPS)
                etSuccess.setText(Prefs.DEFAULT_SUCCESS)
                etFace.setText(Prefs.DEFAULT_FACE)
                etConfirm.setText(Prefs.DEFAULT_CONFIRM)
                etFail.setText(Prefs.DEFAULT_FAIL)
                toast("已填入推荐值，检查后点「保存」")
            }
            btnSaveSteps.setOnClickListener { save() }
        }
    }

    private fun load() = with(binding) {
        etOnSteps.setText(Prefs.getStepsRaw(this@StepsActivity, ClockType.ON))
        etOffSteps.setText(Prefs.getStepsRaw(this@StepsActivity, ClockType.OFF))
        etSuccess.setText(Prefs.getSuccessRaw(this@StepsActivity))
        etFace.setText(Prefs.getFaceRaw(this@StepsActivity))
        etConfirm.setText(Prefs.getConfirmRaw(this@StepsActivity))
        etFail.setText(Prefs.getFailRaw(this@StepsActivity))
        cbOcr.isChecked = Prefs.isOcrEnabled(this@StepsActivity)
        cbKill.isChecked = Prefs.isKillBefore(this@StepsActivity)
    }

    private fun save() = with(binding) {
        Prefs.setStepsRaw(this@StepsActivity, ClockType.ON, etOnSteps.text.toString())
        Prefs.setStepsRaw(this@StepsActivity, ClockType.OFF, etOffSteps.text.toString())
        Prefs.setSuccessRaw(this@StepsActivity, etSuccess.text.toString())
        Prefs.setFaceRaw(this@StepsActivity, etFace.text.toString())
        Prefs.setConfirmRaw(this@StepsActivity, etConfirm.text.toString())
        Prefs.setFailRaw(this@StepsActivity, etFail.text.toString())
        toast("已保存")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
