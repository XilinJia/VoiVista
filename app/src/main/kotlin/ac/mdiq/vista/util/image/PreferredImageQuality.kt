package ac.mdiq.vista.util.image

import android.content.Context
import ac.mdiq.vista.R
import ac.mdiq.vista.extractor.Image.ResolutionLevel

enum class PreferredImageQuality {
    NONE,
    LOW,
    MEDIUM,
    HIGH;

    fun toResolutionLevel(): ResolutionLevel {
        return when (this) {
            LOW -> ResolutionLevel.LOW
            MEDIUM -> ResolutionLevel.MEDIUM
            HIGH -> ResolutionLevel.HIGH
            NONE -> ResolutionLevel.UNKNOWN
        }
    }

    companion object {
        @JvmStatic
        fun fromPreferenceKey(context: Context, key: String): PreferredImageQuality {
            return when (key) {
                context.getString(R.string.image_quality_none_key) -> NONE
                context.getString(R.string.image_quality_low_key) -> LOW
                context.getString(R.string.image_quality_high_key) -> HIGH
                else -> MEDIUM /* default to medium */
            }
        }
    }
}
