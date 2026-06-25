package com.example.umbrella

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SlotState { AVAILABLE, EMPTY, BROKEN }

data class BuildingStatus(
    val name: String,
    val totalCount: Int,
    val slots: MutableList<SlotState>
) {
    val leftCount: Int get() = slots.count { it == SlotState.AVAILABLE }
}

object AppSession {
    private val fmt = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).apply {
        timeZone = java.util.TimeZone.getTimeZone("Asia/Seoul")
    }

    const val BASE_URL = "https://umbrella-server-production-8cd6.up.railway.app"

    @Volatile var jwtToken: String? = null

    var isLoggedIn: Boolean = false
    var userId: String = ""
    var userName: String = "사용자"
    var userPoint: Int = 0

    fun syncLoginData(response: LoginResponse, id: String) {
        isLoggedIn = true
        userId = response.user.id.toString()
        userName = response.user.name
        userPoint = response.user.balance

        // 서버가 "Bearer " 접두사를 붙여 보낼 수도 있어서 순수 토큰만 추출
        val raw = response.accessToken
        jwtToken = if (raw.startsWith("Bearer ", ignoreCase = true)) raw.substring(7).trim() else raw.trim()
    }

    // 로그아웃, 토큰 만료 시 호출
    fun clearSession() {
        jwtToken = null
        isLoggedIn = false
        userId = ""
        userName = "사용자"
        userPoint = 0
        clearRentalInfo()
    }

    // 대여 상태
    var isRenting: Boolean = false
    var currentRentalId: String? = null
    var rentedLocation: String = ""
    var rentedSlot: Int = 0
    var rentedTime: String = ""
    var returnDueTime: String = "기한 미정"
    var depositPaid: Boolean = false
    var rentedBuildingIndex: Int = -1

    fun syncRentalStatus(rental: RentalStatus, location: String) {
        isRenting = true
        currentRentalId = rental.id.toString()
        rentedLocation = location
        rentedSlot = rental.slotId
        rentedTime = rental.rentedAt
        returnDueTime = rental.dueAt ?: "기한 미정"
    }

    fun clearRentalInfo() {
        isRenting = false
        currentRentalId = null
        rentedLocation = ""
        rentedSlot = 0
        rentedTime = ""
        returnDueTime = "기한 미정"
        depositPaid = false
        rentedBuildingIndex = -1
    }

    // 서버 QR 스캔 응답이 오기 전, 대여 화면에서 먼저 보여줄 임시 상태
    fun startRental(buildingIndex: Int, location: String, slot: Int) {
        isRenting = true
        rentedBuildingIndex = buildingIndex
        rentedLocation = location
        rentedSlot = slot
        rentedTime = fmt.format(Date())
        returnDueTime = "기한 확인 중..."
    }

    fun endRental(isBroken: Boolean = false) {
        clearRentalInfo()
    }

    // 디지털관 1층(index 0)만 서버와 실제 연동되고, 나머지 건물은 UI 시연용 더미 데이터
    val buildingList = mutableListOf(
        BuildingStatus("디지털관 1층", 4, mutableListOf(SlotState.AVAILABLE, SlotState.EMPTY, SlotState.AVAILABLE, SlotState.AVAILABLE)),
        BuildingStatus("글로벌관 1층", 4, mutableListOf(SlotState.EMPTY, SlotState.EMPTY, SlotState.EMPTY, SlotState.EMPTY)),
        BuildingStatus("테크노관 1층", 4, mutableListOf(SlotState.AVAILABLE, SlotState.AVAILABLE, SlotState.EMPTY, SlotState.EMPTY)),
        BuildingStatus("도서관 입구",  4, mutableListOf(SlotState.EMPTY, SlotState.AVAILABLE, SlotState.EMPTY, SlotState.EMPTY)),
        BuildingStatus("학생회관 1층", 4, mutableListOf(SlotState.AVAILABLE, SlotState.AVAILABLE, SlotState.AVAILABLE, SlotState.AVAILABLE))
    )

    fun updateBuildingSlots(serverSlots: List<SlotStatus>) {
        if (buildingList.isNotEmpty() && serverSlots.isNotEmpty()) {
            serverSlots.forEach { slot ->
                if (slot.slotNumber in 1..4) {
                    val state = when (slot.status) {
                        "available" -> SlotState.AVAILABLE
                        "disabled"  -> SlotState.BROKEN
                        else        -> SlotState.EMPTY // occupied
                    }
                    buildingList[0].slots[slot.slotNumber - 1] = state
                }
            }
        }
    }

    fun setSlotState(buildingIndex: Int, slotNumber: Int, state: SlotState) {
        if (buildingIndex in buildingList.indices &&
            slotNumber in 1..buildingList[buildingIndex].totalCount) {
            buildingList[buildingIndex].slots[slotNumber - 1] = state
        }
    }
}
