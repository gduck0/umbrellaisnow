package com.example.umbrella

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

class BuildingListActivity : BaseActivity() {

    private val cardIds = listOf(
        R.id.cardDigital, R.id.cardGlobal,
        R.id.cardTechno, R.id.cardLibrary, R.id.cardStudent
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_building_list)
        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        refreshBuildingData()
    }

    private fun refreshBuildingData() {
        RetrofitClient.service.getSlots().enqueue(object : Callback<List<SlotStatus>> {
            override fun onResponse(call: Call<List<SlotStatus>>, response: Response<List<SlotStatus>>) {
                if (response.isSuccessful) {
                    response.body()?.let { AppSession.updateBuildingSlots(it) }
                }
                // 실패해도 로컬 더미 데이터로 카드는 그려준다
                setupCards()
            }
            override fun onFailure(call: Call<List<SlotStatus>>, t: Throwable) {
                android.util.Log.e("BuildingList", "데이터 로드 실패: ${t.message}")
                setupCards()
            }
        })
    }

    private fun setupCards() {
        cardIds.forEachIndexed { index, id ->
            val cardView = findViewById<View>(id) ?: return@forEachIndexed
            val data = AppSession.buildingList.getOrNull(index) ?: return@forEachIndexed

            val tvName   = cardView.findViewById<TextView>(R.id.tvLocationName)
            val tvStatus = cardView.findViewById<TextView>(R.id.tvStatusText)

            tvName?.text = data.name

            // 디지털관(index 0)만 서버 실제 데이터, 나머지는 준비 중 표시
            val isServerConnected = index == 0

            if (!isServerConnected) {
                tvStatus?.text = "준비 중"
                tvStatus?.setTextColor(Color.parseColor("#999999"))
                cardView.setOnClickListener {
                    Toast.makeText(this, "${data.name}은 아직 준비 중입니다.", Toast.LENGTH_SHORT).show()
                }
                cardView.alpha = 0.5f
                return@forEachIndexed
            }

            cardView.alpha = 1.0f
            if (data.leftCount > 0) {
                tvStatus?.text = "잔여: ${data.leftCount}개 (총 ${data.totalCount}개)"
                tvStatus?.setTextColor(Color.parseColor("#004A8D"))
            } else {
                tvStatus?.text = "잔여: 0개 (대여 불가)"
                tvStatus?.setTextColor(Color.parseColor("#D32F2F"))
            }

            cardView.setOnClickListener {
                if (AppSession.isRenting) {
                    Toast.makeText(this, "이미 우산을 대여 중입니다.\n먼저 반납 후 이용해주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (AppSession.userPoint < 3000) {
                    Toast.makeText(this, "보유 포인트가 부족합니다.\n보증금(3,000P)을 먼저 충전해주세요.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (data.leftCount > 0) {
                    startActivity(
                        Intent(this, SelectActivity::class.java).apply {
                            putExtra("BUILDING_INDEX", index)
                            putExtra("BUILDING_NAME", data.name)
                        }
                    )
                } else {
                    Toast.makeText(this, "${data.name}에는 남은 우산이 없습니다.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
