package com.example.umbrella

import com.google.gson.annotations.SerializedName

// 사용자

data class UserOut(
    val id: Int,
    val email: String?,
    val name: String,
    val phone: String?,
    val balance: Int,
    @SerializedName("created_at") val createdAt: String
)

data class LoginResponse(
    @SerializedName("access_token") val accessToken: String,
    @SerializedName("token_type")   val tokenType: String,
    @SerializedName("expires_at")   val expiresAt: String,
    val user: UserOut
)

data class LoginRequest(
    val email: String,
    val password: String,
    val remember_me: Boolean = false
)

data class SignupRequest(
    val email: String,
    val name: String,
    val password: String,
    val password_confirm: String
)

// 슬롯

data class SlotStatus(
    val id: Int,
    @SerializedName("location_id")      val locationId: Int?,
    @SerializedName("location_name")    val locationName: String?,
    @SerializedName("slot_number")      val slotNumber: Int,
    val status: String,
    @SerializedName("umbrella_present") val umbrellaPresent: Boolean,
    @SerializedName("updated_at")       val updatedAt: String
)

data class SlotReportRequest(
    val reason: String,
    val description: String? = null
)

data class SlotReportOut(
    val report: Map<String, Any>,
    val slot: SlotStatus,
    val message: String
)

// QR / 대여

data class QrTokenOut(
    val action: String,
    val token: String,
    @SerializedName("expires_at")    val expiresAt: String,
    @SerializedName("slot_id")       val slotId: Int,
    @SerializedName("location_id")   val locationId: Int?,
    @SerializedName("location_name") val locationName: String?,
    @SerializedName("slot_number")   val slotNumber: Int?,
    @SerializedName("rental_id")     val rentalId: Int?,
    @SerializedName("ttl_seconds")   val ttlSeconds: Int
)

data class RentQrRequest(
    @SerializedName("slot_id") val slotId: Int
)

data class ReturnQrRequest(
    @SerializedName("rental_id")   val rentalId: Int? = null,
    @SerializedName("return_type") val returnType: String = "normal"
)

// 대여 목록 조회(GET /api/rentals)는 slots/locations와 JOIN해 위치 정보를 함께 내려준다
data class RentalStatus(
    val id: Int,
    @SerializedName("slot_id")       val slotId: Int,
    val status: String,
    @SerializedName("created_at")    val rentedAt: String,
    @SerializedName("due_at")        val dueAt: String?,
    @SerializedName("returned_at")   val returnedAt: String? = null,
    @SerializedName("location_name") val locationName: String? = null,
    @SerializedName("slot_number")   val slotNumber: Int? = null,
    @SerializedName("location_id")   val locationId: Int? = null
)

// 대여 상세 조회는 slot을 중첩 객체로 주지 않고 location_id, slot_number,
// location_name을 rental과 같은 레벨로 평탄화해서 내려준다. slot?.xxx 형태로 쓰지 않는다.
data class RentalDetail(
    val id: Int,
    @SerializedName("slot_id")       val slotId: Int,
    val status: String,
    @SerializedName("created_at")    val rentedAt: String?,
    @SerializedName("due_at")        val dueAt: String?,
    @SerializedName("returned_at")   val returnedAt: String? = null,
    @SerializedName("location_id")   val locationId: Int?,
    @SerializedName("location_name") val locationName: String?,
    @SerializedName("slot_number")   val slotNumber: Int?
)

// 신고

data class ReportRequest(
    @SerializedName("rental_id")   val rentalId: Int? = null,
    @SerializedName("description") val description: String? = null
)

// 하드웨어

data class HardwareScanRequest(val token: String)

data class HardwareScanOut(
    val unlock: Boolean,
    val action: String,
    @SerializedName("slot_id")        val slotId: Int,
    @SerializedName("location_id")    val locationId: Int?,
    @SerializedName("location_name")  val locationName: String?,
    @SerializedName("slot_number")    val slotNumber: Int?,
    @SerializedName("rental_id")      val rentalId: Int,
    @SerializedName("return_type")    val returnType: String?,
    @SerializedName("unlock_seconds") val unlockSeconds: Int,
    val message: String
)

data class SensorEventRequest(val present: Boolean)

data class SensorEventOut(
    val event: String,
    val slot: SlotStatus? = null
)

// 결제 / 포인트

data class ChargeRequest(
    @SerializedName("user_id") val userId: Int,
    @SerializedName("amount")  val amount: Int,
    @SerializedName("method")  val method: String
)

data class ChargeResponse(
    val balance: Int,
    val message: String
)

// 환불도 음수 amount로 같은 엔드포인트를 재사용한다
data class DevRechargeRequest(
    @SerializedName("amount") val amount: Int,
    @SerializedName("note")   val note: String? = "앱 테스트 충전"
)

data class WalletResponse(
    @SerializedName("user_id") val userId: Int,
    val balance: Int,
    val transactions: List<Map<String, Any>>
)

// 슬롯 유지보수

data class SlotMaintenanceRequest(
    @SerializedName("umbrella_present") val umbrellaPresent: Boolean = true
)

// 관리자

data class AdminUserOut(
    val id: Int,
    val email: String?,
    val name: String,
    val phone: String?,
    val balance: Int,
    @SerializedName("created_at") val createdAt: String
)

// 앱 홈

data class AppHomeResponse(
    val user: UserOut,
    @SerializedName("point_balance")  val pointBalance: Int,
    @SerializedName("locked_deposit") val lockedDeposit: Int,
    @SerializedName("deposit_amount") val depositAmount: Int,
    @SerializedName("current_rental") val currentRental: RentalDetail? = null
)
