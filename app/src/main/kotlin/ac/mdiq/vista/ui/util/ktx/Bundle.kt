package ac.mdiq.vista.ui.util.ktx

import android.os.Bundle
import android.os.Parcelable
import androidx.core.os.BundleCompat

inline fun <reified T : Parcelable> Bundle.parcelableArrayList(key: String?): ArrayList<T>? {
    return BundleCompat.getParcelableArrayList(this, key, T::class.java)
}
