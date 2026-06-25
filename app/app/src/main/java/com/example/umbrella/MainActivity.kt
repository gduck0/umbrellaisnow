package com.example.umbrella

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.toColorInt
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.Locale

class MainActivity : BaseActivity() {

    // 앱 타이틀을 3초 안에 5번 탭하면 관리자 비밀번호 입력창이 뜬다
    private var adminTapCount = 0
    private val adminTapHandler = Handler(Looper.getMainLooper())
    private val resetTapRunnable = Runnable { adminTapCount = 0 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<View>(R.id.btnGoRent).setOnClickListener {
            startActivity(Intent(this, BuildingListActivity::class.java))
        }
        findViewById<View>(R.id.btnGoPayment).setOnClickListener {
            startActivity(Intent(this, PaymentActivity::class.java))
        }
        findViewById<View?>(R.id.btnRentalHistory)?.setOnClickListener {
            startActivity(Intent(this, RentalHistoryActivity::class.java))
        }
        findViewById<View?>(R.id.btnLogout)?.setOnClickListener {
            showLogoutDialog()
        }

        findViewById<View?>(R.id.tvAppTitle)?.setOnClickListener {
            adminTapCount++
            adminTapHandler.removeCallbacks(resetTapRunnable)
            adminTapHandler.postDelayed(resetTapRunnable, 3000)
            if (adminTapCount >= 5) {
                adminTapCount = 0
                showAdminPasswordDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncHomeData()
    }

    private fun syncHomeData() {
        showLoading(true)
        RetrofitClient.service.getAppHome().enqueue(object : Callback<AppHomeResponse> {
            override fun onResponse(call: Call<AppHomeResponse>, response: Response<AppHomeResponse>) {
                showLoading(false)
                if (response.isSuccessful) {
                    val body = response.body() ?: run { updateDashboardUi(); return }
                    AppSession.userPoint = body.pointBalance
                    AppSession.userName  = body.user.name

                    val rental = body.currentRental
                    if (rental != null) {
                        AppSession.isRenting         = true
                        AppSession.currentRentalId   = rental.id.toString()
                        AppSession.rentedTime        = rental.rentedAt ?: ""
                        AppSession.returnDueTime     = rental.dueAt ?: "기한 미정"
                        AppSession.rentedLocation    = rental.locationName ?: "알 수 없는 위치"
                        AppSession.rentedSlot        = rental.slotNumber ?: rental.slotId
                        AppSession.rentedBuildingIndex = if (rental.locationId == 1) 0 else -1
                    } else {
                        AppSession.clearRentalInfo()
                    }
                } else if (response.code() == 401) {
                    handleSessionExpired(); return
                }
                updateDashboardUi()
            }
            override fun onFailure(call: Call<AppHomeResponse>, t: Throwable) {
                showLoading(false)
                android.util.Log.w("MainActivity", "홈 데이터 로드 실패: ${t.message}")
                updateDashboardUi()
            }
        })
    }

    private fun showLoading(show: Boolean) {
        findViewById<ProgressBar?>(R.id.progressBar)?.visibility =
            if (show) View.VISIBLE else View.GONE
        findViewById<View?>(R.id.btnGoRent)?.isEnabled = !show
    }

    private fun showAdminPasswordDialog() {
        val etPw = EditText(this).apply {
            hint = "관리자 비밀번호"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setPadding(48, 24, 48, 0)
        }
        AlertDialog.Builder(this)
            .setTitle("관리자 인증")
            .setView(etPw)
            .setPositiveButton("확인") { _, _ ->
                if (etPw.text.toString() == AdminActivity.ADMIN_PASSWORD) {
                    startActivity(Intent(this, AdminActivity::class.java))
                } else {
                    Toast.makeText(this, "비밀번호가 올바르지 않습니다.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showLogoutDialog() {
        AlertDialog.Builder(this)
            .setTitle("로그아웃")
            .setMessage("로그아웃 하시겠습니까?")
            .setPositiveButton("로그아웃") { _, _ -> performLogout() }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun performLogout() {
        RetrofitClient.service.logout().enqueue(object : Callback<Map<String, Any>> {
            override fun onResponse(call: Call<Map<String, Any>>, response: Response<Map<String, Any>>) {}
            override fun onFailure(call: Call<Map<String, Any>>, t: Throwable) {}
        })
        AppSession.clearSession()
        getSharedPreferences(LoginActivity.PREF_NAME, MODE_PRIVATE).edit().apply {
            putBoolean(LoginActivity.KEY_KEEP_LOGIN, false)
            remove(LoginActivity.KEY_TOKEN)
            remove(LoginActivity.KEY_USER_ID)
            remove(LoginActivity.KEY_USER_PW)
            apply()
        }
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
    }

    private fun handleSessionExpired() {
        AppSession.clearSession()
        startActivity(Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("SESSION_EXPIRED", true)
        })
    }

    private fun updateDashboardUi() {
        findViewById<TextView>(R.id.tvGreeting).text =
            getString(R.string.greeting_format, AppSession.userName.ifEmpty { "사용자" })
        findViewById<TextView>(R.id.tvUserPoint).text =
            String.format(Locale.KOREA, "%,d P", AppSession.userPoint)

        val locked = if (AppSession.isRenting) 3000 else 0
        findViewById<TextView>(R.id.tvDepositInfo).text =
            getString(R.string.deposit_info_format, locked)

        val tvEmoji   = findViewById<TextView>(R.id.tvStatusEmoji)
        val tvSummary = findViewById<TextView>(R.id.tvStatusSummary)
        val layout    = findViewById<View>(R.id.layoutStatusSummary)
        val tvArrow   = findViewById<View>(R.id.tvStatusArrow)

        if (AppSession.isRenting) {
            tvEmoji.text = "🔵"
            val remaining = DateUtils.remainingTime(AppSession.returnDueTime)
            tvSummary.text = "${AppSession.rentedLocation} ${AppSession.rentedSlot}번 슬롯\n반납 기한: $remaining"
            tvSummary.setTextColor("#004A8D".toColorInt())
            tvSummary.setTypeface(null, android.graphics.Typeface.BOLD)
            tvArrow.visibility = View.VISIBLE
            layout.setOnClickListener { startActivity(Intent(this, StatusActivity::class.java)) }
        } else {
            tvEmoji.text = "⚪"
            tvSummary.text = getString(R.string.no_rental_status)
            tvSummary.setTextColor("#666666".toColorInt())
            tvSummary.setTypeface(null, android.graphics.Typeface.NORMAL)
            tvArrow.visibility = View.GONE
            layout.setOnClickListener(null)
        }
    }
}
