package com.storelense.gateBt.data.remote

import com.storelense.gateBt.data.remote.dto.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    @POST("api/auth/login")
    suspend fun login(@Body req: LoginRequest): Response<ApiResponse<LoginData>>

    @POST("api/auth/refresh")
    suspend fun refresh(@Body req: RefreshRequest): Response<ApiResponse<RefreshData>>

    @GET("api/inventory/epc-by-ean/{ean}")
    suspend fun getEpcsByEan(
        @Path("ean")        ean:     String,
        @Query("storeId")   storeId: String
    ): Response<ApiResponse<EpcsByEanResponse>>

    @GET("api/inventory/identify-epc/{epc}")
    suspend fun identifyEpc(
        @Path("epc")        epc:     String,
        @Query("storeId")   storeId: String
    ): Response<ApiResponse<IdentifyEpcResponse>>

    @POST("api/inventory/epc/sold")
    suspend fun markEpcsSold(@Body req: MarkEpcsSoldRequest): Response<ApiResponse<MarkEpcsSoldResponse>>

    @POST("api/inventory/non-rfid/sold")
    suspend fun markNonRfidSold(@Body req: MarkNonRfidSoldRequest): Response<ApiResponse<Map<String, Int>>>

    @POST("api/gate/checks")
    suspend fun recordGateCheck(@Body req: GateCheckRequest): Response<ApiResponse<GateCheckDto>>

    @PATCH("api/gate/checks/{id}/resolution")
    suspend fun resolveGateCheck(
        @Path("id")  id: String,
        @Body        req: GateCheckResolutionRequest
    ): Response<ApiResponse<Unit>>

    @GET("api/gate/checks/bills/{billRef}")
    suspend fun lookupBill(
        @Path("billRef")    billRef: String,
        @Query("storeId")   storeId: String
    ): Response<ApiResponse<BillLookupResponse>>

    @GET("api/gate/checks/my-summary")
    suspend fun getMyGateCheckSummary(
        @Query("storeId")   storeId: String
    ): Response<ApiResponse<GateCheckSummaryDto>>

    @GET("api/gate/checks/my-recent")
    suspend fun getMyRecentGateChecks(
        @Query("storeId")   storeId: String,
        @Query("limit")     limit:   Int = 20
    ): Response<ApiResponse<List<GateCheckDto>>>

    @GET("api/gate/checks/bills")
    suspend fun getPendingBills(
        @Query("storeId")     storeId:     String,
        @Query("pendingOnly") pendingOnly: Boolean = true,
        @Query("size")        size:        Int = 50
    ): Response<ApiResponse<PageResponse<BillSummaryDto>>>

    @GET("api/stores/{storeId}/features")
    suspend fun getStoreFeatures(
        @Path("storeId")    storeId: String
    ): Response<ApiResponse<List<StoreFeatureDto>>>
}
