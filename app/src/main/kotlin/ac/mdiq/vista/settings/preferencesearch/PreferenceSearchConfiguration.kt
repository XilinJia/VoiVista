package ac.mdiq.vista.settings.preferencesearch

import android.text.TextUtils
import android.util.Pair
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import org.apache.commons.text.similarity.FuzzyScore
import java.util.*
import java.util.stream.Collectors
import java.util.stream.Stream

class PreferenceSearchConfiguration {
    var searcher: PreferenceSearchFunction = PreferenceFuzzySearchFunction()
        set(searcher) {
            field = Objects.requireNonNull(searcher)
        }

    val parserIgnoreElements: List<String> = listOf(PreferenceCategory::class.java.simpleName)
    val parserContainerElements: List<String> = listOf(PreferenceCategory::class.java.simpleName, PreferenceScreen::class.java.simpleName)

    fun interface PreferenceSearchFunction {
        fun search(allAvailable: Stream<PreferenceSearchItem>, keyword: String): Stream<PreferenceSearchItem>
    }

    class PreferenceFuzzySearchFunction : PreferenceSearchFunction {
        override fun search(allAvailable: Stream<PreferenceSearchItem>, keyword: String): Stream<PreferenceSearchItem> {
            val maxScore = (keyword.length + 1) * 3 - 2 // First can't get +2 bonus score

            return allAvailable // General search
                // Check all fields if anyone contains something that kind of matches the keyword
                .map { item: PreferenceSearchItem -> FuzzySearchGeneralDTO(item, keyword) }
                .filter { dto: FuzzySearchGeneralDTO -> dto.score / maxScore >= 0.3f }
                .map { obj: FuzzySearchGeneralDTO -> obj.item } // Specific search - Used for determining order of search results
                // Calculate a score based on specific search fields
                .map { item: PreferenceSearchItem -> FuzzySearchSpecificDTO(item, keyword) }
                .sorted(Comparator.comparingDouble { obj: FuzzySearchSpecificDTO -> obj.score }
                    .reversed())
                .map { obj: FuzzySearchSpecificDTO -> obj.item } // Limit the amount of search results
                .limit(20)
        }

        internal class FuzzySearchGeneralDTO(val item: PreferenceSearchItem, keyword: String) {
            val score: Float = FUZZY_SCORE.fuzzyScore(TextUtils.join(";", item.allRelevantSearchFields), keyword).toFloat()
        }

        internal class FuzzySearchSpecificDTO(val item: PreferenceSearchItem, keyword: String) {
            val score: Double = WEIGHT_MAP.entries.stream()
                .map { entry: Map.Entry<java.util.function.Function<PreferenceSearchItem, String>, Float> ->
                    Pair(entry.key.apply(item), entry.value)
                }
                .filter { pair: Pair<String, Float> -> pair.first.isNotEmpty() }
                .collect(Collectors.averagingDouble { pair: Pair<String, Float> ->
                    (FUZZY_SCORE.fuzzyScore(pair.first, keyword) * pair.second).toDouble()
                })

            companion object {
                private val WEIGHT_MAP: Map<java.util.function.Function<PreferenceSearchItem, String>, Float> =
                    java.util.Map.of( // The user will most likely look for the title -> prioritize it
                        java.util.function.Function { obj: PreferenceSearchItem -> obj.title },
                        1.5f,  // The summary is also important as it usually contains a larger desc
                        // Example: Searching for '4k' → 'show higher resolution' is shown
                        java.util.function.Function { obj: PreferenceSearchItem -> obj.summary },
                        1f,  // Entries are also important as they provide all known/possible values
                        // Example: Searching where the resolution can be changed to 720p
                        java.util.function.Function { obj: PreferenceSearchItem -> obj.entries },
                        1f
                    )
            }
        }

        companion object {
            private val FUZZY_SCORE = FuzzyScore(Locale.ROOT)
        }
    }
}
