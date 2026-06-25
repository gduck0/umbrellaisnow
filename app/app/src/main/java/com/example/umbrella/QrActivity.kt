package com.example.umbrella

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class QrActivity : BaseActivity() {

    private var mode: String = "RENT"
    private var isBroken: Boolean = false
    private var countDownTimer: CountDownTimer? = null
    private var currentToken: String? = null
    private var serverSlotId: Int = -1
    private var buildingIndex: Int = -1
    private var buildingName: String = ""
    private var slotNumber: Int = -1

    // 하드웨어가 직접 QR을 스캔하는 환경에서는 앱이 이 상태 변화를 감지해야 한다.
    // 3초마다 서버에 대여 상태를 물어보고, 변화가 확인되면 자동으로 다음 화면으로 넘어간다.
    private val pollHandler = Handler(Looper.getMainLooper())
    private var isPolling = false
    private val POLL_INTERVAL_MS = 3000L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr)

        mode = intent.getStringExtra("MODE") ?: "RENT"
        isBroken = intent.getBooleanExtra("IS_BROKEN", false)
        buildingName = intent.getStringExtra("LOCATION") ?: ""
        buildingIndex = intent.getIntExtra("BUILDING_INDEX", -1)
        slotNumber = intent.getIntExtra("SLOT_NUMBER", -1)

        val tvTitle       = findViewById<TextView>(R.id.tvQrTitle)
        val tvInstruction = findViewById<TextView>(R.id.tvQrInstruction)
        val tvLocation    = findViewById<TextView>(R.id.tvLocationInfo)
        val btnComplete   = findViewById<Button>(R.id.btnComplete)
        val layoutBroken  = findViewById<View>(R.id.layoutBrokenStatus)
        val tvTimer       = findViewById<TextView>(R.id.tvTimer)
        val ivQrCode      = findViewById<ImageView>(R.id.ivQrCode)

        if (mode == "RENT") {
            tvTitle.text = "대여 QR 인식"
            tvInstruction.text = "라즈베리파이 카메라 모듈에\n아래 QR 코드를 스캔하세요."
            tvLocation.text = "$buildingName - ${slotNumber}번 슬롯"
            layoutBroken.visibility = View.GONE

            val actualSlotId = intent.getIntExtra("SLOT_ID", -1)
            if (actualSlotId != -1) {
                requestRentQr(actualSlotId, ivQrCode)
            } else {
                Toast.makeText(this, "슬롯 정보가 없습니다.", Toast.LENGTH_SHORT).show()
                finish()
            }
        } else {
            tvTitle.text = "반납 QR 인식"
            tvInstruction.text = "라즈베리파이 카메라 모듈에\n아래 QR 코드를 스캔하세요."
            tvLocation.text = "$buildingName - 반납 중"
            layoutBroken.visibility = if (isBroken) View.VISIBLE else View.GONE
            requestReturnQr(ivQrCode)
        }

        startTimer(tvTimer)
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }

        // 실제 하드웨어가 없는 환경에서 QR 스캔 → 센서 감지 과정을 대신 트리거하는 테스트용 버튼
        btnComplete.text = "인식 완료 (키오스크 스캔 테스트)"
        btnComplete.setOnClickListener {
            val token = currentToken
            if (token == null) {
                Toast.makeText(this, "아직 QR 코드가 생성되지 않았습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (serverSlotId == -1) {
                Toast.makeText(this, "슬롯 ID를 아직 받지 못했습니다.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            btnComplete.isEnabled = false
            simulateHardwareProcess(token)
        }
    }

    private fun requestRentQr(slotId: Int, ivQrCode: ImageView) {
        RetrofitClient.service.generateQr(RentQrRequest(slotId)).enqueue(object : Callback<QrTokenOut> {
            override fun onResponse(call: Call<QrTokenOut>, response: Response<QrTokenOut>) {
                if (response.isSuccessful) {
                    val body = response.body()
                    currentToken = body?.token
                    serverSlotId = body?.slotId ?: -1
                    if (currentToken != null) {
                        generateAndDisplayQrCode(currentToken!!, ivQrCode)
                        startPolling()
                    } else {
                        Toast.makeText(this@QrActivity, "QR 토큰을 받지 못했습니다.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val errMsg = when (response.code()) {
                        402  -> "포인트가 부족합니다. (보증금 3,000P 필요)"
                        400  -> "이용 불가 슬롯입니다. 다른 슬롯을 선택하세요."
                        409  -> "이미 대여 중입니다. 반납 후 이용해주세요."
                        else -> "대여 QR 생성 실패 (${response.code()})"
                    }
                    Toast.makeText(this@QrActivity, errMsg, Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            override fun onFailure(call: Call<QrTokenOut>, t: Throwable) {
                Toast.makeText(this@QrActivity, "서버 연결 오류: ${t.message}", Toast.LENGTH_SHORT).show()
                finish()
            }
        })
    }

    private fun requestReturnQr(ivQrCode: ImageView) {
        val returnType = if (isBroken) "damage_report" else "normal"
        val rentalId   = AppSession.currentRentalId?.toIntOrNull()

        RetrofitClient.service.generateReturnQr(ReturnQrRequest(rentalId = rentalId, returnType = returnType))
            .enqueue(object : Callback<QrTokenOut> {
                override fun onResponse(call: Call<QrTokenOut>, response: Response<QrTokenOut>) {
                    if (response.isSuccessful) {
                        val body = response.body()
                        currentToken = body?.token
                        serverSlotId = body?.slotId ?: -1
                        if (currentToken != null) {
                            generateAndDisplayQrCode(currentToken!!, ivQrCode)
                            startPolling()
                        } else {
                            Toast.makeText(this@QrActivity, "QR 토큰을 받지 못했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        when (response.code()) {
                            // 409는 직전 반납 시도가 pending_return 상태로 남아있을 때 발생.
                            // rental_id 없이 재요청하면 서버가 활성 대여를 다시 찾아 새 QR을 발급해준다.
                            409 -> {
                                if (rentalId != null) {
                                    Toast.makeText(this@QrActivity, "이미 반납 진행 중입니다. 다시 시도합니다.", Toast.LENGTH_SHORT).show()
                                    retryReturnQrWithoutRentalId(returnType, ivQrCode)
                                } else {
                                    Toast.makeText(this@QrActivity, "반납 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해주세요.", Toast.LENGTH_LONG).show()
                                    finish()
                                }
                            }
                            404 -> {
                                Toast.makeText(this@QrActivity, "진행 중인 대여가 없습니다.", Toast.LENGTH_LONG).show()
                                finish()
                            }
                            else -> {
                                Toast.makeText(this@QrActivity, "반납 QR 생성 실패 (${response.code()})", Toast.LENGTH_LONG).show()
                                finish()
                            }
                        }
                    }
                }
                override fun onFailure(call: Call<QrTokenOut>, t: Throwable) {
                    Toast.makeText(this@QrActivity, "서버 연결 오류: ${t.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            })
    }

    private fun retryReturnQrWithoutRentalId(returnType: String, ivQrCode: ImageView) {
        RetrofitClient.service.generateReturnQr(ReturnQrRequest(rentalId = null, returnType = returnType))
            .enqueue(object : Callback<QrTokenOut> {
                override fun onResponse(call: Call<QrTokenOut>, response: Response<QrTokenOut>) {
                    if (response.isSuccessful) {
                        val body = response.body()
                        currentToken = body?.token
                        serverSlotId = body?.slotId ?: -1
                        if (currentToken != null) {
                            generateAndDisplayQrCode(currentToken!!, ivQrCode)
                            startPolling()
                        } else {
                            Toast.makeText(this@QrActivity, "QR 토큰을 받지 못했습니다.", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@QrActivity, "반납 QR 재시도 실패 (${response.code()})", Toast.LENGTH_LONG).show()
                        finish()
                    }
                }
                override fun onFailure(call: Call<QrTokenOut>, t: Throwable) {
                    Toast.makeText(this@QrActivity, "서버 연결 오류: ${t.message}", Toast.LENGTH_SHORT).show()
                    finish()
                }
            })
    }

    // 키오스크 스캔 테스트 버튼이 호출하는 함수. QR 스캔 → IR 센서 순서로
    // 실제 하드웨어가 보낼 두 단계 요청을 앱에서 대신 보내본다.
    private fun simulateHardwareProcess(token: String) {
        RetrofitClient.service.simulateHardwareScan(HardwareScanRequest(token))
            .enqueue(object : Callback<HardwareScanOut> {
                override fun onResponse(call: Call<HardwareScanOut>, response: Response<HardwareScanOut>) {
                    if (response.isSuccessful) {
                        val hardwareOut = response.body() ?: return
                        val confirmedSlotId = hardwareOut.slotId
                        val rentalId = hardwareOut.rentalId

                        if (mode == "RENT") {
                            AppSession.currentRentalId = rentalId.toString()
                        }

                        val isPresent = mode != "RENT"
                        RetrofitClient.service.simulateSensorEvent(confirmedSlotId, SensorEventRequest(present = isPresent))
                            .enqueue(object : Callback<SensorEventOut> {
                                override fun onResponse(call: Call<SensorEventOut>, response2: Response<SensorEventOut>) {
                                    if (response2.isSuccessful) {
                                        if (mode == "RENT") {
                                            AppSession.startRental(buildingIndex, buildingName, slotNumber)
                                            syncBalanceFromServer()
                                            Toast.makeText(this@QrActivity,
                                                "대여 완료! 우산을 뽑아주세요.\n⚠ 고장 발견 시 5분 내 신고 가능합니다.",
                                                Toast.LENGTH_LONG).show()
                                        } else {
                                            AppSession.endRental(isBroken)
                                            syncBalanceFromServer()
                                            val msg = if (isBroken) "고장 신고 반납 완료 (보증금 차감)" else "반납 완료! 보증금 3,000P 환불됨"
                                            Toast.makeText(this@QrActivity, msg, Toast.LENGTH_LONG).show()
                                        }
                                        goHome()
                                    } else {
                                        Toast.makeText(this@QrActivity, "센서 처리 오류 (${response2.code()})", Toast.LENGTH_SHORT).show()
                                        findViewById<Button>(R.id.btnComplete).isEnabled = true
                                    }
                                }
                                override fun onFailure(call: Call<SensorEventOut>, t: Throwable) {
                                    Toast.makeText(this@QrActivity, "네트워크 오류", Toast.LENGTH_SHORT).show()
                                    findViewById<Button>(R.id.btnComplete).isEnabled = true
                                }
                            })
                    } else {
                        Toast.makeText(this@QrActivity, "만료되거나 잘못된 QR입니다.", Toast.LENGTH_SHORT).show()
                        findViewById<Button>(R.id.btnComplete).isEnabled = true
                    }
                }
                override fun onFailure(call: Call<HardwareScanOut>, t: Throwable) {
                    Toast.makeText(this@QrActivity, "네트워크 오류", Toast.LENGTH_SHORT).show()
                    findViewById<Button>(R.id.btnComplete).isEnabled = true
                }
            })
    }

    private fun syncBalanceFromServer() {
        RetrofitClient.service.getMe().enqueue(object : Callback<UserOut> {
            override fun onResponse(call: Call<UserOut>, response: Response<UserOut>) {
                if (response.isSuccessful) {
                    response.body()?.let { AppSession.userPoint = it.balance }
                }
            }
            override fun onFailure(call: Call<UserOut>, t: Throwable) {
                android.util.Log.w("QrActivity", "잔액 재조회 실패: ${t.message}")
            }
        })
    }

    private fun generateAndDisplayQrCode(content: String, imageView: ImageView) {
        Thread {
            try {
                val writer = com.google.zxing.qrcode.QRCodeWriter()
                val bitMatrix = writer.encode(content, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512)
                val w = bitMatrix.width; val h = bitMatrix.height
                val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565)
                val pixels = IntArray(w * h)
                for (y in 0 until h) for (x in 0 until w)
                    pixels[y * w + x] = if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
                bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
                runOnUiThread { imageView.setImageBitmap(bitmap) }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread { Toast.makeText(this@QrActivity, "QR 이미지 생성 실패", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    private fun startTimer(tvTimer: TextView) {
        countDownTimer = object : CountDownTimer(60000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val s = (millisUntilFinished / 1000).toInt()
                tvTimer.text = String.format(java.util.Locale.KOREA, "남은 시간: %02d:%02d", s / 60, s % 60)
            }
            override fun onFinish() {
                tvTimer.text = "남은 시간: 00:00"
                Toast.makeText(this@QrActivity, "QR이 만료되었습니다. 다시 시도해주세요.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }.start()
    }

    private fun goHome() {
        startActivity(Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP })
        finish()
    }

    // 하드웨어 연동용 폴링

    private fun startPolling() {
        if (isPolling) return
        isPolling = true
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun stopPolling() {
        isPolling = false
        pollHandler.removeCallbacks(pollRunnable)
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isPolling) return
            checkRentalStatusChange()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    // 대여는 pending_pickup → active, 반납은 pending_return → completed로 바뀌는 순간을 감지한다.
    // 라즈베리파이가 QR 스캔과 센서 이벤트를 서버에 직접 보내면 이 흐름으로 화면이 자동 전환된다.
    private fun checkRentalStatusChange() {
        RetrofitClient.service.getMyActiveRental().enqueue(object : Callback<RentalDetail?> {
            override fun onResponse(call: Call<RentalDetail?>, response: Response<RentalDetail?>) {
                if (!response.isSuccessful) return
                val rental = response.body()

                if (mode == "RENT") {
                    if (rental?.status == "active") {
                        stopPolling()
                        AppSession.startRental(buildingIndex, buildingName, slotNumber)
                        AppSession.currentRentalId = rental.id.toString()
                        syncBalanceFromServer()
                        runOnUiThread {
                            Toast.makeText(this@QrActivity,
                                "대여 완료! 우산을 뽑아주세요.\n⚠ 고장 발견 시 5분 내 신고 가능합니다.",
                                Toast.LENGTH_LONG).show()
                            goHome()
                        }
                    }
                } else {
                    if (rental == null || rental.status in listOf("completed", "defect_reported", "self_damage_reported")) {
                        stopPolling()
                        AppSession.endRental(isBroken)
                        syncBalanceFromServer()
                        runOnUiThread {
                            val msg = if (isBroken) "고장 신고 반납 완료 (보증금 차감)"
                            else "반납 완료! 보증금 3,000P 환불됨"
                            Toast.makeText(this@QrActivity, msg, Toast.LENGTH_LONG).show()
                            goHome()
                        }
                    }
                }
            }
            override fun onFailure(call: Call<RentalDetail?>, t: Throwable) {
                android.util.Log.w("QrActivity", "폴링 실패: ${t.message}")
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        stopPolling()
    }
}
