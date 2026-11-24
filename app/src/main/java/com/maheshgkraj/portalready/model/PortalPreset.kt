package com.maheshgkraj.portalready.model

enum class DocType { PHOTO, SIGNATURE, CERTIFICATE_PDF }

data class PortalPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val category: String,
    val docType: DocType,
    val widthPx: Int,
    val heightPx: Int,
    val minKb: Int,
    val maxKb: Int,
    val requiresWhiteBg: Boolean = true,
    val supportsDateStamp: Boolean = false
)

object PresetCatalog {
    val allPresets = listOf(
        PortalPreset("kpsc_photo", "KPSC Passport Photo", "150x200 px • 10 KB - 30 KB • White BG", "KPSC / State Govt", DocType.PHOTO, 150, 200, 10, 30, true, false),
        PortalPreset("kpsc_sig", "KPSC Signature", "150x100 px • 5 KB - 30 KB • High Contrast", "KPSC / State Govt", DocType.SIGNATURE, 150, 100, 5, 30, true, false),
        PortalPreset("upsc_photo", "UPSC Civil Services Photo", "400x400 px • 20 KB - 200 KB • Name & Date", "UPSC / Central", DocType.PHOTO, 400, 400, 20, 200, true, true),
        PortalPreset("upsc_sig", "UPSC Signature", "350x350 px • 20 KB - 100 KB • Black Ink", "UPSC / Central", DocType.SIGNATURE, 350, 350, 20, 100, true, false),
        PortalPreset("ssc_photo", "SSC / RRB Photo", "200x230 px • 20 KB - 50 KB", "SSC / Banking", DocType.PHOTO, 200, 230, 20, 50, true, false),
        PortalPreset("ibps_sig", "IBPS / SBI Signature", "140x60 px • 10 KB - 20 KB", "SSC / Banking", DocType.SIGNATURE, 140, 60, 10, 20, true, false),
        PortalPreset("nta_photo", "NTA (NEET / JEE) Photo", "350x450 px • 10 KB - 200 KB • Name & Date", "College / NTA", DocType.PHOTO, 350, 450, 10, 200, true, true),
        PortalPreset("pan_photo", "PAN Card (NSDL / UTI) Photo", "213x213 px • 10 KB - 50 KB (300 DPI)", "ID / KYC", DocType.PHOTO, 213, 213, 10, 50, true, false)
    )
}