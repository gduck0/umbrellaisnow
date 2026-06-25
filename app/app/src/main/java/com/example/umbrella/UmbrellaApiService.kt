package com.example.umbrella

import retrofit2.Call
import retrofit2.http.*

interface UmbrellaApiService {

    // 인증
    @POST("/api/auth/login")
    fun login(@Body request: LoginRequest): Call<LoginResponse>

    // 회원가입 성공 시 서버가 201 + 토큰/유저 정보를 함께 반환해 바로 로그인 처리
    @POST("/api/auth/register")
    fun signup(@Body request: SignupRequest): Call<LoginResponse>

    @POST("/api/auth/logout")
    fun logout(): Call<Map<String, Any>>

    // 내 정보
    @GET("/api/me")
    fun getMe(): Call<UserOut>

    // 포인트 잔액과 현재 대여 정보를 한 번에 조회
    @GET("/api/app/home")
    fun getAppHome(): Call<AppHomeResponse>

    @GET("/api/me/rentals/active")
    fun getMyActiveRental(): Call<RentalDetail?>

    // 슬롯
    @GET("/api/slots")
    fun getSlots(): Call<List<SlotStatus>>

    @POST("/api/slots/{slot_id}/reports")
    fun reportSlotIssue(
        @Path("slot_id") slotId: Int,
        @Body request: SlotReportRequest
    ): Call<SlotReportOut>

    // QR
    @POST("/api/qr/rent")
    fun generateQr(@Body request: RentQrRequest): Call<QrTokenOut>

    @POST("/api/qr/return")
    fun generateReturnQr(@Body request: ReturnQrRequest): Call<QrTokenOut>

    // 대여 목록
    @GET("/api/rentals")
    fun getMyRentals(@Query("user_id") userId: Int): Call<List<RentalStatus>>

    // 하드웨어 시뮬레이션 (실기기 연동 전 테스트용)
    @POST("/api/hardware/qr/scan")
    fun simulateHardwareScan(@Body request: HardwareScanRequest): Call<HardwareScanOut>

    @POST("/api/hardware/slots/{slot_id}/sensor")
    fun simulateSensorEvent(
        @Path("slot_id") slotId: Int,
        @Body request: SensorEventRequest
    ): Call<SensorEventOut>

    // 슬롯 유지보수
    @POST("/api/maintenance/slots/{slot_id}/enable")
    fun enableSlot(
        @Path("slot_id") slotId: Int,
        @Body request: SlotMaintenanceRequest
    ): Call<SlotStatus>

    @POST("/api/maintenance/slots/{slot_id}/disable")
    fun disableSlot(@Path("slot_id") slotId: Int): Call<SlotStatus>

    // 관리자
    @GET("/api/admin/users")
    fun adminGetAllUsers(): Call<List<AdminUserOut>>

    @GET("/api/admin/users/search")
    fun adminSearchUser(@Query("email") email: String): Call<List<AdminUserOut>>

    @DELETE("/api/admin/users/{user_id}")
    fun adminDeleteUser(@Path("user_id") userId: Int): Call<Void>

    // 신고 — 대여 직후 불량 신고 시 직전 반납자에게 패널티가 부과되고 본인 보증금은 환불됨
    @POST("/api/reports/defect")
    fun reportDefect(@Body request: ReportRequest): Call<Map<String, Any>>

    // 포인트 충전 — 실제 결제 연동 전이라 서버에 직접 적립하는 방식
    @POST("/api/users/{user_id}/wallet/recharge")
    fun devRechargeWallet(
        @Path("user_id") userId: Int,
        @Body request: DevRechargeRequest
    ): Call<WalletResponse>
}
