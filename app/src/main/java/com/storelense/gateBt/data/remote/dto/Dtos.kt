package com.storelense.gateBt.data.remote.dto

data class ApiResponse<T>(
    val success: Boolean = false,
    val data:    T?      = null,
    val message: String? = null
)

// ── Auth ──────────────────────────────────────────────────────────────────────

data class LoginRequest(val username: String, val password: String)

data class LoginData(
    val accessToken:  String,
    val refreshToken: String,
    val userId:       String,
    val username:     String,
    val role:         String,
    val storeId:      String?
)

data class RefreshRequest(val refreshToken: String)

data class RefreshData(
    val accessToken:  String,
    val refreshToken: String
)

// ── Gate / EAN resolution ─────────────────────────────────────────────────────

data class EpcsByEanResponse(
    val ean:           String,
    val sku:           String?,
    val productName:   String,
    val epcs:          List<String> = emptyList(),
    /** null = RFID unknown/not configured; false = product has no RFID tag (verify by barcode) */
    val isRfidEnabled: Boolean?     = null,
    val imageUrl:      String?      = null
)

data class IdentifyEpcResponse(
    val epc:               String        = "",
    val productId:         String?      = null,
    val sku:                String?      = null,
    val productName:        String?      = null,
    /** identify-epc can return one EAN or multiple EANs depending on the API version. */
    val ean:                String?      = null,
    val eans:                List<String> = emptyList(),
    val statusInStore:       String?      = null,
    val zoneName:            String?      = null,
    val alreadyRegistered:   Boolean      = false,
    val imageUrl:            String?      = null
)

// ── Mark sold ─────────────────────────────────────────────────────────────────

data class MarkEpcsSoldRequest(
    val storeId: String,
    val epcs:    List<String>
)

data class MarkEpcsSoldResponse(
    val marked:   Int,
    val total:    Int,
    val notFound: Int
)

data class MarkNonRfidSoldRequest(
    val storeId: String,
    val items:   List<NonRfidSaleItem>
)

data class NonRfidSaleItem(
    val ean: String,
    val qty: Int
)

data class PageResponse<T>(
    val content:       List<T> = emptyList(),
    val page:           Int    = 0,
    val size:           Int    = 0,
    val totalElements:  Long   = 0,
    val totalPages:     Int    = 0,
    val last:            Boolean = true
)

/** One row in the pending-bills list — a bill registered at POS that has never
 *  passed the guard app (status stays PENDING until a gate check stamps it). */
data class BillSummaryDto(
    val id:            String,
    val billRef:       String,
    val storeId:       String,
    val cashierId:     String?,
    val totalItems:    Int,
    val totalValue:    String?,
    val status:        String,
    val createdAt:     String?,
    val gateCheckedAt: String?
)

// ── Gate check ────────────────────────────────────────────────────────────────

data class GateCheckRequest(
    val storeId:       String,
    val billRef:       String,
    val expectedCount: Int,
    val matchedCount:  Int,
    val extraCount:    Int,
    val outcome:       String,
    val epcsMatched:   List<String>,
    val epcsExtra:     List<String>
)

data class GateCheckSummaryDto(
    val totalChecks:     Int = 0,
    val released:        Int = 0,
    val flagged:         Int = 0,
    val abandoned:       Int = 0,
    val totalExtraItems: Int = 0
)

data class GateCheckResolutionRequest(val resolution: String)

data class GateCheckDto(
    val id:            String,
    val billRef:       String?,
    val outcome:       String,
    val expectedCount: Int,
    val matchedCount:  Int,
    val extraCount:    Int,
    val checkedAt:     String?,
    val epcsExtra:     List<String> = emptyList()
)

// ── Bill lookup ───────────────────────────────────────────────────────────────

data class BillLookupResponse(
    val billRef:       String,
    val status:        String,
    val items:         List<BillLookupItem>,
    val gateCheckedAt: String? = null
)

data class BillLookupItem(
    val ean:           String,
    val qty:           Int,
    val productName:   String?,
    val sku:           String?,
    /** From products.is_rfid_enabled, joined at bill-lookup time. null = unknown/no
     *  barcode row yet (treat as RFID); only a confirmed false means "verify by barcode". */
    val isRfidEnabled: Boolean? = null,
    val imageUrl:      String? = null,
    val unitPrice:     java.math.BigDecimal? = null
)

// ── Store features ────────────────────────────────────────────────────────────

data class StoreFeatureDto(
    val feature: String,
    val enabled: Boolean
)
