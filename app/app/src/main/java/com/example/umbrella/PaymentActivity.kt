package com.example.umbrella

import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class PaymentActivity : BaseActivity() {

    private var chargeAmount: Int = 3000

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payment)

        val tvCurrentPoint = findViewById<TextView>(R.id.tvCurrentPoint)
        val tvChargeAmount = findViewById<TextView>(R.id.tvChargeAmount)
        val btnMinus  = findViewById<Button>(R.id.btnMinus)
        val btnPlus   = findViewById<Button>(R.id.btnPlus)
        val btnCharge = findViewById<Button>(R.id.btnCharge)
        val btnBack   = findViewById<ImageView>(R.id.btnBack)
        val btnRefund = findViewById<Button?>(R.id.btnRefund)

        updateUi(tvCurrentPoint, tvChargeAmount, btnCharge)
        btnBack.setOnClickListener { finish() }

        btnMinus.setOnClickListener {
            if (chargeAmount > 1000) {
                chargeAmount -= 1000
                updateUi(tvCurrentPoint, tvChargeAmount, btnCharge)
            } else {
                Toast.makeText(this, "최소 충전 금액은 1,000원입니다.", Toast.LENGTH_SHORT).show()
            }
        }
        btnPlus.setOnClickListener {
            if (chargeAmount < 100_000) {
                chargeAmount += 1000
                updateUi(tvCurrentPoint, tvChargeAmount, btnCharge)
            }
        }
        btnCharge.setOnClickListener { performCharge(btnCharge) }
        btnRefund?.setOnClickListener { showRefundDialog() }
    }

    override fun onResume() {
        super.onResume()
        syncBalanceFromServer()
    }

    private fun syncBalanceFromServer() {
        RetrofitClient.service.getMe().enqueue(object : Callback<UserOut> {
            override fun onResponse(call: Call<UserOut>, response: Response<UserOut>) {
                if (response.isSuccessful) {
                    response.body()?.let {
                        AppSession.userPoint = it.balance
                        findViewById<TextView?>(R.id.tvCurrentPoint)?.text =
                            "${String.format("%,d", AppSession.userPoint)}P"
                    }
                }
            }
            override fun onFailure(call: Call<UserOut>, t: Throwable) {
                android.util.Log.w("PaymentActivity", "잔액 조회 실패: ${t.message}")
            }
        })
    }

    private fun performCharge(btn: Button) {
        val userId = AppSession.userId.toIntOrNull() ?: run {
            Toast.makeText(this, "로그인 정보가 없습니다.", Toast.LENGTH_SHORT).show(); return
        }
        btn.isEnabled = false
        RetrofitClient.service.devRechargeWallet(userId, DevRechargeRequest(amount = chargeAmount))
            .enqueue(object : Callback<WalletResponse> {
                override fun onResponse(call: Call<WalletResponse>, response: Response<WalletResponse>) {
                    btn.isEnabled = true
                    if (response.isSuccessful) {
                        val newBalance = response.body()?.balance ?: (AppSession.userPoint + chargeAmount)
                        AppSession.userPoint = newBalance
                        Toast.makeText(this@PaymentActivity,
                            "${String.format("%,d", chargeAmount)}P 충전 완료!\n잔액: ${String.format("%,d", newBalance)}P",
                            Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        Toast.makeText(this@PaymentActivity, "충전 실패 (${response.code()})", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<WalletResponse>, t: Throwable) {
                    btn.isEnabled = true
                    Toast.makeText(this@PaymentActivity, "네트워크 오류", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun showRefundDialog() {
        if (AppSession.isRenting) {
            Toast.makeText(this, "대여 중에는 환불할 수 없습니다.\n반납 후 이용해주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val currentBalance = AppSession.userPoint
        if (currentBalance <= 0) {
            Toast.makeText(this, "환불 가능한 포인트가 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("포인트 환불")
            .setMessage("보유 포인트 ${String.format("%,d", currentBalance)}P를 전액 환불하시겠습니까?\n환불 후 잔액은 0P가 됩니다.")
            .setPositiveButton("환불하기") { _, _ -> performRefund(currentBalance) }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun performRefund(amount: Int) {
        val userId = AppSession.userId.toIntOrNull() ?: return
        // 별도 환불 API 없이 recharge에 음수 금액을 보내서 차감 처리
        RetrofitClient.service.devRechargeWallet(userId, DevRechargeRequest(amount = -amount, note = "포인트 환불"))
            .enqueue(object : Callback<WalletResponse> {
                override fun onResponse(call: Call<WalletResponse>, response: Response<WalletResponse>) {
                    if (response.isSuccessful) {
                        AppSession.userPoint = response.body()?.balance ?: 0
                        Toast.makeText(this@PaymentActivity,
                            "${String.format("%,d", amount)}P 환불 완료!", Toast.LENGTH_SHORT).show()
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        Toast.makeText(this@PaymentActivity, "환불 실패 (${response.code()})", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onFailure(call: Call<WalletResponse>, t: Throwable) {
                    Toast.makeText(this@PaymentActivity, "네트워크 오류", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun updateUi(tvPoint: TextView, tvAmount: TextView, btnCharge: Button) {
        tvPoint.text   = "${String.format("%,d", AppSession.userPoint)}P"
        tvAmount.text  = String.format("%,d", chargeAmount)
        btnCharge.text = "${String.format("%,d", chargeAmount)}원 충전하기"
    }
}
