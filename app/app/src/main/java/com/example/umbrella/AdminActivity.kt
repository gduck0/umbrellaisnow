package com.example.umbrella

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

// 앱 타이틀 5번 탭 + 비밀번호로 진입하는 숨겨진 관리자 화면.
// 슬롯 점검 상태 해제와 사용자 포인트 조정·삭제를 처리한다.
class AdminActivity : BaseActivity() {

    companion object {
        const val ADMIN_PASSWORD = "0000"
    }

    private var userList: List<AdminUserOut> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_admin)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        val btnTabSlot = findViewById<Button>(R.id.btnTabSlot)
        val btnTabUser = findViewById<Button>(R.id.btnTabUser)
        val layoutSlot = findViewById<View>(R.id.layoutSlotManage)
        val layoutUser = findViewById<View>(R.id.layoutUserManage)

        btnTabSlot.setOnClickListener {
            layoutSlot.visibility = View.VISIBLE
            layoutUser.visibility = View.GONE
            loadSlots()
        }
        btnTabUser.setOnClickListener {
            layoutSlot.visibility = View.GONE
            layoutUser.visibility = View.VISIBLE
            loadAllUsers()
        }

        findViewById<Button>(R.id.btnSearchUser).setOnClickListener {
            val email = findViewById<EditText>(R.id.etSearchEmail).text.toString().trim()
            if (email.isEmpty()) loadAllUsers() else searchUser(email)
        }

        loadSlots()
    }

    // 슬롯 관리

    private fun loadSlots() {
        val container = findViewById<LinearLayout>(R.id.layoutSlotList)
        container.removeAllViews()
        showSlotLoading(true)

        RetrofitClient.service.getSlots().enqueue(object : Callback<List<SlotStatus>> {
            override fun onResponse(call: Call<List<SlotStatus>>, response: Response<List<SlotStatus>>) {
                showSlotLoading(false)
                if (response.isSuccessful) {
                    response.body()?.forEach { slot -> addSlotRow(container, slot) }
                } else {
                    toast("슬롯 조회 실패 (${response.code()})")
                }
            }
            override fun onFailure(call: Call<List<SlotStatus>>, t: Throwable) {
                showSlotLoading(false)
                toast("네트워크 오류")
            }
        })
    }

    private fun addSlotRow(container: LinearLayout, slot: SlotStatus) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val statusEmoji = when (slot.status) {
            "available" -> "🟢"
            "occupied"  -> "🔵"
            "disabled"  -> "🔴"
            else        -> "⚪"
        }
        val statusText = when (slot.status) {
            "available" -> "정상"
            "occupied"  -> "대여 중"
            "disabled"  -> "점검"
            else        -> slot.status
        }

        val tvInfo = TextView(this).apply {
            text = "$statusEmoji  ${slot.slotNumber}번 슬롯  [$statusText]"
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(android.graphics.Color.parseColor("#333333"))
        }

        val btnAction = Button(this).apply {
            when (slot.status) {
                "disabled" -> {
                    text = "정상화"
                    setBackgroundColor(android.graphics.Color.parseColor("#2E7D32"))
                    setTextColor(android.graphics.Color.WHITE)
                    setOnClickListener { confirmSlotEnable(slot) }
                }
                "available" -> {
                    text = "비활성화"
                    setBackgroundColor(android.graphics.Color.parseColor("#D32F2F"))
                    setTextColor(android.graphics.Color.WHITE)
                    setOnClickListener { confirmSlotDisable(slot) }
                }
                else -> {
                    text = "대여 중"
                    isEnabled = false
                    setBackgroundColor(android.graphics.Color.parseColor("#CCCCCC"))
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        row.addView(tvInfo)
        row.addView(btnAction)

        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            ).apply { setMargins(0, 4, 0, 0) }
            setBackgroundColor(android.graphics.Color.parseColor("#EEEEEE"))
        }

        container.addView(row)
        container.addView(divider)
    }

    private fun confirmSlotEnable(slot: SlotStatus) {
        AlertDialog.Builder(this)
            .setTitle("슬롯 정상화")
            .setMessage("${slot.slotNumber}번 슬롯을 정상 상태로 복구하시겠습니까?")
            .setPositiveButton("정상화") { _, _ ->
                RetrofitClient.service.enableSlot(slot.id, SlotMaintenanceRequest(umbrellaPresent = true))
                    .enqueue(object : Callback<SlotStatus> {
                        override fun onResponse(call: Call<SlotStatus>, response: Response<SlotStatus>) {
                            if (response.isSuccessful) { toast("정상화 완료"); loadSlots() }
                            else toast("실패 (${response.code()})")
                        }
                        override fun onFailure(call: Call<SlotStatus>, t: Throwable) { toast("네트워크 오류") }
                    })
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmSlotDisable(slot: SlotStatus) {
        AlertDialog.Builder(this)
            .setTitle("슬롯 비활성화")
            .setMessage("${slot.slotNumber}번 슬롯을 점검 상태로 변경하시겠습니까?")
            .setPositiveButton("비활성화") { _, _ ->
                RetrofitClient.service.disableSlot(slot.id)
                    .enqueue(object : Callback<SlotStatus> {
                        override fun onResponse(call: Call<SlotStatus>, response: Response<SlotStatus>) {
                            if (response.isSuccessful) { toast("비활성화 완료"); loadSlots() }
                            else toast("실패 (${response.code()})")
                        }
                        override fun onFailure(call: Call<SlotStatus>, t: Throwable) { toast("네트워크 오류") }
                    })
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // 사용자 관리

    private fun loadAllUsers() {
        showUserLoading(true)
        RetrofitClient.service.adminGetAllUsers().enqueue(object : Callback<List<AdminUserOut>> {
            override fun onResponse(call: Call<List<AdminUserOut>>, response: Response<List<AdminUserOut>>) {
                showUserLoading(false)
                if (response.isSuccessful) {
                    userList = response.body() ?: emptyList()
                    renderUserList()
                } else toast("사용자 조회 실패 (${response.code()})")
            }
            override fun onFailure(call: Call<List<AdminUserOut>>, t: Throwable) {
                showUserLoading(false); toast("네트워크 오류")
            }
        })
    }

    private fun searchUser(email: String) {
        showUserLoading(true)
        RetrofitClient.service.adminSearchUser(email).enqueue(object : Callback<List<AdminUserOut>> {
            override fun onResponse(call: Call<List<AdminUserOut>>, response: Response<List<AdminUserOut>>) {
                showUserLoading(false)
                if (response.isSuccessful) {
                    userList = response.body() ?: emptyList()
                    renderUserList()
                    if (userList.isEmpty()) toast("검색 결과가 없습니다.")
                } else toast("검색 실패 (${response.code()})")
            }
            override fun onFailure(call: Call<List<AdminUserOut>>, t: Throwable) {
                showUserLoading(false); toast("네트워크 오류")
            }
        })
    }

    private fun renderUserList() {
        val container = findViewById<LinearLayout>(R.id.layoutUserList)
        container.removeAllViews()
        userList.forEach { user -> addUserRow(container, user) }
    }

    private fun addUserRow(container: LinearLayout, user: AdminUserOut) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 12) }
        }

        val tvName = TextView(this).apply {
            text = "${user.name}  (ID: ${user.id})"
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor("#333333"))
        }
        val tvEmail = TextView(this).apply {
            text = user.email ?: "-"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#666666"))
            setPadding(0, 4, 0, 0)
        }
        val tvBalance = TextView(this).apply {
            text = "잔액: ${String.format("%,d", user.balance)}P"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#004A8D"))
            setPadding(0, 4, 0, 12)
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val btnPoint = Button(this).apply {
            text = "포인트 조정"
            textSize = 12f
            setBackgroundColor(android.graphics.Color.parseColor("#004A8D"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 8, 0) }
            setOnClickListener { showPointDialog(user) }
        }
        val btnDelete = Button(this).apply {
            text = "회원 삭제"
            textSize = 12f
            setBackgroundColor(android.graphics.Color.parseColor("#D32F2F"))
            setTextColor(android.graphics.Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { confirmDeleteUser(user) }
        }

        btnRow.addView(btnPoint)
        btnRow.addView(btnDelete)

        card.addView(tvName)
        card.addView(tvEmail)
        card.addView(tvBalance)
        card.addView(btnRow)
        container.addView(card)
    }

    private fun showPointDialog(user: AdminUserOut) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }
        val etAmount = EditText(this).apply {
            hint = "조정할 금액 (음수 입력 시 차감)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        layout.addView(etAmount)

        AlertDialog.Builder(this)
            .setTitle("${user.name} 포인트 조정\n현재 잔액: ${String.format("%,d", user.balance)}P")
            .setView(layout)
            .setPositiveButton("적용") { _, _ ->
                val amount = etAmount.text.toString().toIntOrNull()
                if (amount == null || amount == 0) {
                    toast("올바른 금액을 입력해주세요.")
                    return@setPositiveButton
                }
                val note = if (amount > 0) "관리자 포인트 지급" else "관리자 포인트 차감"
                RetrofitClient.service.devRechargeWallet(
                    user.id, DevRechargeRequest(amount = amount, note = note)
                ).enqueue(object : Callback<WalletResponse> {
                    override fun onResponse(call: Call<WalletResponse>, response: Response<WalletResponse>) {
                        if (response.isSuccessful) {
                            val newBalance = response.body()?.balance ?: 0
                            toast("완료! 새 잔액: ${String.format("%,d", newBalance)}P")
                            loadAllUsers()
                        } else toast("실패 (${response.code()})")
                    }
                    override fun onFailure(call: Call<WalletResponse>, t: Throwable) { toast("네트워크 오류") }
                })
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun confirmDeleteUser(user: AdminUserOut) {
        AlertDialog.Builder(this)
            .setTitle("회원 삭제")
            .setMessage("${user.name} (${user.email}) 계정을 삭제하시겠습니까?\n이 작업은 되돌릴 수 없습니다.")
            .setPositiveButton("삭제") { _, _ ->
                RetrofitClient.service.adminDeleteUser(user.id)
                    .enqueue(object : Callback<Void> {
                        override fun onResponse(call: Call<Void>, response: Response<Void>) {
                            if (response.isSuccessful || response.code() == 204) {
                                toast("${user.name} 계정이 삭제되었습니다.")
                                loadAllUsers()
                            } else {
                                val msg = if (response.code() == 409)
                                    "진행 중인 대여가 있어 삭제할 수 없습니다."
                                else "삭제 실패 (${response.code()})"
                                toast(msg)
                            }
                        }
                        override fun onFailure(call: Call<Void>, t: Throwable) { toast("네트워크 오류") }
                    })
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showSlotLoading(show: Boolean) {
        findViewById<ProgressBar?>(R.id.progressBarSlot)?.visibility =
            if (show) View.VISIBLE else View.GONE
    }

    private fun showUserLoading(show: Boolean) {
        findViewById<ProgressBar?>(R.id.progressBarUser)?.visibility =
            if (show) View.VISIBLE else View.GONE
    }

    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
