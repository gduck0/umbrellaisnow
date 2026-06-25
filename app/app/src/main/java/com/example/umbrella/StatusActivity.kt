package com.example.umbrella

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class StatusActivity : BaseActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var countdownRunning = false

    private var rentedAtIso: String? = null
    private var currentRentalStatus: String? = null

    // 1분마다 반납 기한 카운트다운과 불량 신고 버튼 상태를 갱신
    private val countdownRunnable = object : Runnable {
        override fun run() {
            updateCountdown()
            updateDefectButton(currentRentalStatus)
            handler.postDelayed(this, 60_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnGoHome).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            })
        }
        findViewById<Button>(R.id.btnReturn).setOnClickListener { showReturnOptionDialog() }
        findViewById<TextView>(R.id.tvReportError).setOnClickListener { showBrokenReturnConfirm() }
        findViewById<Button?>(R.id.btnReportDefect)?.setOnClickListener { showDefectReportDialog() }
    }

    override fun onResume() {
        super.onResume()
        syncRentalFromServer()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(countdownRunnable)
        countdownRunning = false
    }

    private fun syncRentalFromServer() {
        showLoading(true)
        RetrofitClient.service.getMyActiveRental().enqueue(object : Callback<RentalDetail?> {
            override fun onResponse(call: Call<RentalDetail?>, response: Response<RentalDetail?>) {
                showLoading(false)
                if (response.isSuccessful) {
                    val rental = response.body()
                    if (rental != null) {
                        AppSession.isRenting       = true
                        AppSession.currentRentalId = rental.id.toString()
                        AppSession.rentedLocation  = rental.locationName ?: AppSession.rentedLocation
                        AppSession.rentedSlot      = rental.slotNumber  ?: rental.slotId
                        AppSession.rentedTime      = rental.rentedAt    ?: AppSession.rentedTime
                        AppSession.returnDueTime   = rental.dueAt       ?: "기한 미정"
                        AppSession.rentedBuildingIndex = if (rental.locationId == 1) 0 else -1
                        rentedAtIso = rental.rentedAt
                        currentRentalStatus = rental.status
                        updateUi(rental.status)
                        startCountdown()
                    } else {
                        Toast.makeText(this@StatusActivity, "현재 대여 중인 우산이 없습니다.", Toast.LENGTH_SHORT).show()
                        AppSession.clearRentalInfo()
                        finish()
                    }
                } else {
                    if (!AppSession.isRenting) {
                        Toast.makeText(this@StatusActivity, "대여 정보를 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        updateUi(null)
                        startCountdown()
                    }
                }
            }
            override fun onFailure(call: Call<RentalDetail?>, t: Throwable) {
                showLoading(false)
                if (!AppSession.isRenting) {
                    Toast.makeText(this@StatusActivity, "서버에 연결할 수 없습니다.", Toast.LENGTH_SHORT).show()
                    finish()
                } else {
                    updateUi(null)
                    startCountdown()
                }
            }
        })
    }

    private fun updateUi(rentalStatus: String?) {
        findViewById<TextView>(R.id.tvLocationValue).text =
            "${AppSession.rentedLocation} (${AppSession.rentedSlot}번 슬롯)"
        findViewById<TextView>(R.id.tvTimeValue).text =
            DateUtils.formatDisplay(AppSession.rentedTime).ifEmpty { "-" }
        updateCountdown()
        updateDefectButton(rentalStatus)
    }

    // 불량 신고 버튼은 대여 후 5분 동안만 노출/활성화된다
    private fun updateDefectButton(rentalStatus: String? = null) {
        val btn = findViewById<Button?>(R.id.btnReportDefect) ?: return

        val isActiveStatus = rentalStatus in listOf("pending_pickup", "active")
        val secsLeft = DateUtils.defectReportSecondsLeft(rentedAtIso ?: AppSession.rentedTime)
        val withinLimit = secsLeft > 0

        if (isActiveStatus && withinLimit) {
            btn.visibility = View.VISIBLE
            btn.isEnabled  = true
            val minsLeft = (secsLeft / 60).toInt()
            val secPart  = (secsLeft % 60).toInt()
            btn.text = "🔧 불량 우산 신고 (${minsLeft}분 ${secPart}초 내 가능)"
        } else if (isActiveStatus && !withinLimit) {
            btn.visibility = View.VISIBLE
            btn.isEnabled  = false
            btn.text       = "🔧 불량 신고 가능 시간 초과 (대여 후 5분)"
        } else {
            btn.visibility = View.GONE
        }
    }

    private fun updateCountdown() {
        val tvDue = findViewById<TextView?>(R.id.tvDueTimeValue) ?: return
        val due   = AppSession.returnDueTime
        if (due == "기한 미정" || due == "기한 확인 중...") {
            tvDue.text = due; return
        }
        val formatted = DateUtils.formatDisplay(due)
        val remaining = DateUtils.remainingTime(due)
        tvDue.text = "$formatted\n($remaining)"
        tvDue.setTextColor(android.graphics.Color.parseColor("#D32F2F"))
    }

    private fun startCountdown() {
        if (!countdownRunning) {
            countdownRunning = true
            handler.post(countdownRunnable)
        }
    }

    private fun showLoading(show: Boolean) {
        findViewById<ProgressBar?>(R.id.progressBar)?.visibility =
            if (show) View.VISIBLE else View.GONE
        findViewById<Button?>(R.id.btnReturn)?.isEnabled = !show
    }

    private fun showDefectReportDialog() {
        val secsLeft = DateUtils.defectReportSecondsLeft(rentedAtIso ?: AppSession.rentedTime)
        val minsLeft = (secsLeft / 60).toInt()

        AlertDialog.Builder(this)
            .setTitle("🔧 불량 우산 신고")
            .setMessage(
                "대여한 우산이 이미 고장나 있나요?\n\n" +
                "신고 가능 시간: 약 ${minsLeft}분 남음\n\n" +
                "신고하면:\n" +
                "• 본인 보증금 3,000P 즉시 환불\n" +
                "• 직전 반납자에게 3,000P 패널티 부과\n" +
                "• 해당 슬롯 점검 처리\n\n" +
                "허위 신고 시 불이익이 있을 수 있습니다."
            )
            .setPositiveButton("신고하기") { _, _ -> performDefectReport() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun performDefectReport() {
        val rentalId = AppSession.currentRentalId?.toIntOrNull()
        val btn = findViewById<Button?>(R.id.btnReportDefect)
        btn?.isEnabled = false

        RetrofitClient.service.reportDefect(ReportRequest(rentalId = rentalId))
            .enqueue(object : Callback<Map<String, Any>> {
                override fun onResponse(call: Call<Map<String, Any>>, response: Response<Map<String, Any>>) {
                    if (response.isSuccessful) {
                        RetrofitClient.service.getMe().enqueue(object : Callback<UserOut> {
                            override fun onResponse(call: Call<UserOut>, r: Response<UserOut>) {
                                if (r.isSuccessful) r.body()?.let { AppSession.userPoint = it.balance }
                            }
                            override fun onFailure(call: Call<UserOut>, t: Throwable) {}
                        })
                        AppSession.clearRentalInfo()
                        Toast.makeText(this@StatusActivity, "신고 완료! 보증금이 환불되었습니다.", Toast.LENGTH_LONG).show()
                        startActivity(Intent(this@StatusActivity, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                        })
                    } else {
                        btn?.isEnabled = true
                        val msg = when (response.code()) {
                            404  -> "진행 중인 대여가 없습니다."
                            409  -> "이미 신고된 대여입니다."
                            else -> "신고 실패 (${response.code()})"
                        }
                        Toast.makeText(this@StatusActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<Map<String, Any>>, t: Throwable) {
                    btn?.isEnabled = true
                    Toast.makeText(this@StatusActivity, "네트워크 오류", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun showReturnOptionDialog() {
        AlertDialog.Builder(this)
            .setTitle("반납 방식을 선택해 주세요")
            .setItems(arrayOf("정상 반납 (보증금 반환)", "고장 신고 반납 (보증금 차감)")) { _, which ->
                if (which == 0) goToQr(false) else showBrokenReturnConfirm()
            }
            .show()
    }

    private fun showBrokenReturnConfirm() {
        AlertDialog.Builder(this)
            .setTitle("고장 신고 반납")
            .setMessage("우산 고장을 신고하며 반납하시겠습니까?\n이 경우 보증금(3,000P)은 반환되지 않습니다.")
            .setPositiveButton("신고하며 반납") { _, _ -> goToQr(true) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun goToQr(isBroken: Boolean) {
        startActivity(Intent(this, QrActivity::class.java).apply {
            putExtra("MODE", "RETURN")
            putExtra("IS_BROKEN", isBroken)
            putExtra("LOCATION", AppSession.rentedLocation)
            putExtra("BUILDING_INDEX", AppSession.rentedBuildingIndex)
            putExtra("SLOT_NUMBER", AppSession.rentedSlot)
        })
    }
}
