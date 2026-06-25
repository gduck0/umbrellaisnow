package com.example.umbrella

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class RentalHistoryActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rental_history)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        loadRentalHistory()
    }

    private fun loadRentalHistory() {
        val userId = AppSession.userId.toIntOrNull() ?: run {
            Toast.makeText(this, "로그인 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        val tvEmpty    = findViewById<TextView>(R.id.tvEmptyHistory)
        val listLayout = findViewById<LinearLayout>(R.id.layoutHistoryList)
        val tvLoading  = findViewById<TextView>(R.id.tvLoading)
        val progress   = findViewById<ProgressBar?>(R.id.progressBar)

        tvLoading.visibility  = View.GONE
        progress?.visibility  = View.VISIBLE
        tvEmpty.visibility    = View.GONE
        listLayout.visibility = View.GONE

        RetrofitClient.service.getMyRentals(userId).enqueue(object : Callback<List<RentalStatus>> {
            override fun onResponse(call: Call<List<RentalStatus>>, response: Response<List<RentalStatus>>) {
                progress?.visibility = View.GONE
                if (response.isSuccessful) {
                    val rentals = response.body() ?: emptyList()
                    if (rentals.isEmpty()) {
                        tvEmpty.visibility = View.VISIBLE
                    } else {
                        listLayout.visibility = View.VISIBLE
                        listLayout.removeAllViews()
                        rentals.forEach { addRentalCard(listLayout, it) }
                    }
                } else {
                    Toast.makeText(this@RentalHistoryActivity,
                        "기록 조회 실패 (${response.code()})", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onFailure(call: Call<List<RentalStatus>>, t: Throwable) {
                progress?.visibility = View.GONE
                Toast.makeText(this@RentalHistoryActivity, "네트워크 오류", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun addRentalCard(container: LinearLayout, rental: RentalStatus) {
        val (statusText, statusColor) = when (rental.status) {
            "completed"            -> "반납 완료" to "#2E7D32"
            "active"               -> "대여 중"   to "#004A8D"
            "pending_pickup"       -> "픽업 대기" to "#F57C00"
            "pending_return"       -> "반납 대기" to "#F57C00"
            "defect_reported"      -> "불량 신고" to "#D32F2F"
            "self_damage_reported" -> "훼손 신고" to "#D32F2F"
            else                   -> rental.status to "#666666"
        }

        val rentedStr = DateUtils.formatDisplay(rental.rentedAt)
        val returnStr = if (rental.returnedAt != null) DateUtils.formatDisplay(rental.returnedAt) else "-"
        val dueStr    = DateUtils.formatDisplay(rental.dueAt)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 36, 48, 36)
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
        }

        fun tv(text: String, size: Float, color: String, bold: Boolean = false) =
            TextView(this).apply {
                this.text = text
                textSize = size
                setTextColor(android.graphics.Color.parseColor(color))
                if (bold) setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 6, 0, 0)
            }

        card.addView(tv("[$statusText]  대여 #${rental.id}", 15f, statusColor, true))
        card.addView(tv("대여 일시: $rentedStr", 13f, "#555555"))
        card.addView(tv("반납 일시: $returnStr", 13f, "#555555"))
        card.addView(tv("반납 기한: $dueStr", 13f, "#888888"))
        container.addView(card)
    }
}
