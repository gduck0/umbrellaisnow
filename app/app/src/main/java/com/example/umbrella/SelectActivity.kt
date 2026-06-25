package com.example.umbrella

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.card.MaterialCardView
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class SelectActivity : BaseActivity() {

    private var buildingIndex: Int = -1
    private var buildingName: String = ""

    // 슬롯 카드 클릭 시 실제 slot_id를 꺼내 쓰기 위해 서버 원본 데이터를 보관
    private var serverSlots: List<SlotStatus> = emptyList()

    private data class SlotViews(
        val card: MaterialCardView,
        val tvNum: TextView,
        val tvIcon: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_selection)

        buildingIndex = intent.getIntExtra("BUILDING_INDEX", -1)
        buildingName = intent.getStringExtra("BUILDING_NAME") ?: "건물 정보 없음"

        // 슬롯 카드 클릭으로 대여 흐름이 시작되므로 기존 버튼은 숨김
        findViewById<View?>(R.id.btnRentQr)?.visibility = View.GONE

        findViewById<TextView>(R.id.tvLocationTitle).text = buildingName
        findViewById<ImageView>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refreshSlotData()
    }

    private fun refreshSlotData() {
        RetrofitClient.service.getSlots().enqueue(object : Callback<List<SlotStatus>> {
            override fun onResponse(call: Call<List<SlotStatus>>, response: Response<List<SlotStatus>>) {
                if (response.isSuccessful) {
                    response.body()?.let { slots ->
                        serverSlots = slots
                        AppSession.updateBuildingSlots(slots)
                    }
                }
                setupSlotViews()
            }
            override fun onFailure(call: Call<List<SlotStatus>>, t: Throwable) {
                Toast.makeText(this@SelectActivity, "슬롯 정보 로드 실패", Toast.LENGTH_SHORT).show()
                setupSlotViews()
            }
        })
    }

    private fun setupSlotViews() {
        val slotIds = listOf(R.id.slot1, R.id.slot2, R.id.slot3, R.id.slot4)
        val slotViews = slotIds.map { id ->
            val root = findViewById<View>(id)
            SlotViews(
                card = root as MaterialCardView,
                tvNum = root.findViewById(R.id.tvSlotNum),
                tvIcon = root.findViewById(R.id.tvSlotIcon)
            )
        }

        if (buildingIndex != -1) {
            val building = AppSession.buildingList[buildingIndex]
            slotViews.forEachIndexed { i, views ->
                val state = building.slots[i]
                updateSlotUi(views, i + 1, state)

                views.card.setOnClickListener { handleSlotClick(i, state) }

                views.card.setOnLongClickListener {
                    if (state != SlotState.EMPTY) {
                        showReportDialog(i + 1)
                        true
                    } else false
                }
            }
        }
    }

    private fun handleSlotClick(i: Int, state: SlotState) {
        when (state) {
            SlotState.AVAILABLE -> {
                if (AppSession.userPoint < 3000) {
                    Toast.makeText(this, "포인트가 부족합니다. (보증금 3,000P 필요)", Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, PaymentActivity::class.java))
                    return
                }

                // 슬롯 번호(1-indexed)와 일치하는 서버 slot_id를 찾아 QR 화면에 전달
                val slotNumber = i + 1
                val realSlotId = serverSlots.firstOrNull { it.slotNumber == slotNumber }?.id ?: run {
                    Toast.makeText(this, "슬롯 정보를 확인할 수 없습니다. 새로고침 후 시도해주세요.", Toast.LENGTH_SHORT).show()
                    return
                }

                startActivity(Intent(this, QrActivity::class.java).apply {
                    putExtra("MODE", "RENT")
                    putExtra("LOCATION", buildingName)
                    putExtra("BUILDING_INDEX", buildingIndex)
                    putExtra("SLOT_NUMBER", slotNumber)
                    putExtra("SLOT_ID", realSlotId)
                })
            }
            SlotState.BROKEN -> {
                Toast.makeText(this, "점검 중인 슬롯입니다. 다른 슬롯을 이용해 주세요.", Toast.LENGTH_SHORT).show()
            }
            SlotState.EMPTY -> {
                val isMyRental = AppSession.isRenting &&
                        buildingIndex == AppSession.rentedBuildingIndex &&
                        (i + 1) == AppSession.rentedSlot
                if (isMyRental) {
                    startActivity(Intent(this, StatusActivity::class.java))
                } else {
                    Toast.makeText(this, "이미 다른 사용자가 대여 중인 슬롯입니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateSlotUi(views: SlotViews, num: Int, state: SlotState) {
        views.tvNum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        when (state) {
            SlotState.AVAILABLE -> {
                views.tvNum.text = "${num}번 (대여 가능)"
                views.tvIcon.text = "☂️"
                views.card.setCardBackgroundColor(Color.WHITE)
                views.tvNum.setTextColor(Color.parseColor("#004A8D"))
                views.tvIcon.alpha = 1.0f
            }
            SlotState.EMPTY -> {
                val isMyRental = AppSession.isRenting &&
                        buildingIndex == AppSession.rentedBuildingIndex &&
                        num == AppSession.rentedSlot
                if (isMyRental) {
                    views.tvNum.text = "${num}번 (내 우산 - 반납하기)"
                    views.tvNum.setTextColor(Color.parseColor("#2E7D32"))
                    views.card.setCardBackgroundColor(Color.parseColor("#E8F5E9"))
                    views.tvIcon.text = "👤"
                    views.tvIcon.alpha = 1.0f
                } else {
                    views.tvNum.text = "${num}번 (대여 중)"
                    views.tvIcon.text = "○"
                    views.card.setCardBackgroundColor(Color.parseColor("#F8F9FA"))
                    views.tvNum.setTextColor(Color.parseColor("#999999"))
                    views.tvIcon.alpha = 0.3f
                }
            }
            SlotState.BROKEN -> {
                views.tvNum.text = "🔧 점검 중"
                views.tvIcon.text = "🔧"
                views.card.setCardBackgroundColor(Color.parseColor("#FFF5F5"))
                views.tvNum.setTextColor(Color.RED)
                views.tvIcon.alpha = 1.0f
            }
        }
    }

    private fun showReportDialog(slotNum: Int) {
        val reasons = arrayOf("umbrella_damage", "umbrella_missing", "other")
        val labels  = arrayOf("우산 고장", "우산 없음 (분실 의심)", "기타 신고")

        AlertDialog.Builder(this)
            .setTitle("${slotNum}번 슬롯 신고하기")
            .setItems(labels) { _, which ->
                val serverSlot = serverSlots.firstOrNull { it.slotNumber == slotNum }
                if (serverSlot == null) {
                    Toast.makeText(this, "슬롯 정보를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                RetrofitClient.service.reportSlotIssue(serverSlot.id, SlotReportRequest(reason = reasons[which]))
                    .enqueue(object : Callback<SlotReportOut> {
                        override fun onResponse(call: Call<SlotReportOut>, response: Response<SlotReportOut>) {
                            if (response.isSuccessful) {
                                Toast.makeText(this@SelectActivity,
                                    "신고 접수 완료. 해당 슬롯은 점검 상태로 변경됩니다.", Toast.LENGTH_SHORT).show()
                                refreshSlotData()
                            } else {
                                Toast.makeText(this@SelectActivity,
                                    "신고 실패 (${response.code()})", Toast.LENGTH_SHORT).show()
                            }
                        }
                        override fun onFailure(call: Call<SlotReportOut>, t: Throwable) {
                            Toast.makeText(this@SelectActivity, "네트워크 오류", Toast.LENGTH_SHORT).show()
                        }
                    })
            }
            .setNegativeButton("취소", null)
            .show()
    }
}
