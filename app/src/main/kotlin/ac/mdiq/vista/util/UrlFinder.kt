package ac.mdiq.vista.util

import java.util.regex.Pattern

class UrlFinder {
    companion object {

        //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        // CHANGED: Removed unused code //
        //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        private val IP_ADDRESS: Pattern = Pattern.compile(
            "((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9])\\.(25[0-5]|2[0-4]"
                    + "[0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1]"
                    + "[0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}"
                    + "|[1-9][0-9]|[0-9]))")

        /**
         * Valid UCS characters defined in RFC 3987. Excludes space characters.
         */
        private const val UCS_CHAR = ("["
                + "\u00A0-\uD7FF"
                + "\uF900-\uFDCF"
                + "\uFDF0-\uFFEF"
                + "\uD800\uDC00-\uD83F\uDFFD"
                + "\uD840\uDC00-\uD87F\uDFFD"
                + "\uD880\uDC00-\uD8BF\uDFFD"
                + "\uD8C0\uDC00-\uD8FF\uDFFD"
                + "\uD900\uDC00-\uD93F\uDFFD"
                + "\uD940\uDC00-\uD97F\uDFFD"
                + "\uD980\uDC00-\uD9BF\uDFFD"
                + "\uD9C0\uDC00-\uD9FF\uDFFD"
                + "\uDA00\uDC00-\uDA3F\uDFFD"
                + "\uDA40\uDC00-\uDA7F\uDFFD"
                + "\uDA80\uDC00-\uDABF\uDFFD"
                + "\uDAC0\uDC00-\uDAFF\uDFFD"
                + "\uDB00\uDC00-\uDB3F\uDFFD"
                + "\uDB44\uDC00-\uDB7F\uDFFD"
                + "&&[^\u00A0[\u2000-\u200A]\u2028\u2029\u202F\u3000]]")

        /**
         * Valid characters for IRI label defined in RFC 3987.
         */
        private const val LABEL_CHAR = "a-zA-Z0-9$UCS_CHAR"

        /**
         * RFC 1035 Section 2.3.4 limits the labels to a maximum 63 octets.
         */
        private const val IRI_LABEL = "[" + LABEL_CHAR + "](?:[" + LABEL_CHAR + "_\\-]{0,61}[" + LABEL_CHAR + "]){0,1}"

        //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        // CHANGED: Removed rtsp from supported protocols //
        //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        private const val PROTOCOL = "(?i:http|https)://"

        /* A word boundary or end of input.  This is to stop foo.sure from matching as foo.su */
        private const val WORD_BOUNDARY = "(?:\\b|$|^)"

        private const val USER_INFO = ("(?:[a-zA-Z0-9\\$\\-\\_\\.\\+\\!\\*\\'\\(\\)"
                + "\\,\\;\\?\\&\\=]|(?:\\%[a-fA-F0-9]{2})){1,64}(?:\\:(?:[a-zA-Z0-9\\$\\-\\_"
                + "\\.\\+\\!\\*\\'\\(\\)\\,\\;\\?\\&\\=]|(?:\\%[a-fA-F0-9]{2})){1,25})?\\@")

        private const val PORT_NUMBER = "\\:\\d{1,5}"

        private const val PATH_AND_QUERY = ("[/\\?](?:(?:[" + LABEL_CHAR
                + ";/\\?:@&=#~" // plus optional query params
                + "\\-\\.\\+!\\*'\\(\\),_\\$])|(?:%[a-fA-F0-9]{2}))*")

        /**
         * Regular expression that matches domain names without a TLD.
         */
        private val RELAXED_DOMAIN_NAME = "(?:(?:$IRI_LABEL(?:\\.(?=\\S))?)+|$IP_ADDRESS)"

        //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        // CHANGED: Field visibility was modified //
        //!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
        /**
         * Regular expression to match strings that start with a supported protocol. Rules for domain
         * names and TLDs are more relaxed. TLDs are optional.
         */
        /*package*/
        private val WEB_URL_WITH_PROTOCOL: String = ("("
                + WORD_BOUNDARY
                + "(?:"
                + "(?:" + PROTOCOL + "(?:" + USER_INFO + ")?" + ")"
                + "(?:" + RELAXED_DOMAIN_NAME + ")?"
                + "(?:" + PORT_NUMBER + ")?"
                + ")"
                + "(?:" + PATH_AND_QUERY + ")?"
                + WORD_BOUNDARY
                + ")")

        private val WEB_URL_WITH_PROTOCOL_PATTERN = Pattern.compile(WEB_URL_WITH_PROTOCOL)

        /**
         * @return the first url found in the input, null otherwise.
         */
        @JvmStatic
        fun firstUrlFromInput(input: String?): String? {
            if (input.isNullOrEmpty()) return null
            val matcher = WEB_URL_WITH_PROTOCOL_PATTERN.matcher(input)
            if (matcher.find()) return matcher.group()
            return null
        }
    }
}
