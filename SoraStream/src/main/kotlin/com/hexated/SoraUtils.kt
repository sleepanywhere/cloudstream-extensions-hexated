package com.hexated

import com.hexated.SoraStream.Companion.gdbot
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.base64Encode
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.nicehttp.requestCreator
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI

data class FilmxyCookies(
    val phpsessid: String? = null,
    val wLog: String? = null,
    val wSec: String? = null,
)

fun String.filterIframe(seasonNum: Int?, lastSeason: Int?, year: Int?): Boolean {
    return if (seasonNum != null) {
        if (lastSeason == 1) {
            this.contains(Regex("(?i)(S0?$seasonNum)|(Season\\s0?$seasonNum)|([0-9]{3,4}p)")) && !this.contains(
                "Download",
                true
            )
        } else {
            this.contains(Regex("(?i)(S0?$seasonNum)|(Season\\s0?$seasonNum)")) && !this.contains(
                "Download",
                true
            )
        }
    } else {
        this.contains("$year", true) && !this.contains("Download", true)
    }
}

fun String.filterMedia(title: String?, yearNum: Int?, seasonNum: Int?): Boolean {
    return if (seasonNum != null) {
        when {
            seasonNum > 1 -> this.contains(Regex("(?i)(Season\\s0?1-0?$seasonNum)|(S0?1-S?0?$seasonNum)")) && this.contains(
                "$title",
                true
            ) && this.contains("$yearNum")
            else -> this.contains(Regex("(?i)(Season\\s0?1)|(S0?1)")) && this.contains(
                "$title",
                true
            ) && this.contains("$yearNum")
        }
    } else {
        this.contains("$title", true) && this.contains("$yearNum")
    }
}

suspend fun extractGdbot(url: String): String? {
    val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    )
    val res = app.get(
        "$gdbot/", headers = headers
    )
    val token = res.document.selectFirst("input[name=_token]")?.attr("value")
    val cookiesSet = res.headers.filter { it.first == "set-cookie" }
    val xsrf = cookiesSet.find { it.second.contains("XSRF-TOKEN") }?.second?.substringAfter("XSRF-TOKEN=")
        ?.substringBefore(";")
    val session = cookiesSet.find { it.second.contains("gdtot_proxy_session") }?.second?.substringAfter("gdtot_proxy_session=")
        ?.substringBefore(";")

    val cookies = mapOf(
        "gdtot_proxy_session" to "$session",
        "XSRF-TOKEN" to "$xsrf"
    )
    val requestFile = app.post(
        "$gdbot/file", data = mapOf(
            "link" to url,
            "_token" to "$token"
        ), headers = headers, referer = "$gdbot/", cookies = cookies
    ).document

    return requestFile.selectFirst("div.mt-8 a.float-right")?.attr("href")
}

suspend fun getFilmxyCookies(imdbId: String? = null, season: Int? = null): FilmxyCookies? {

    val url = if (season == null) {
        "${SoraStream.filmxyAPI}/movie/$imdbId"
    } else {
        "${SoraStream.filmxyAPI}/tv/$imdbId"
    }
    val cookieUrl = "${SoraStream.filmxyAPI}/wp-admin/admin-ajax.php"

    val res = session.get(
        url,
        headers = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        ),
    )

    if (!res.isSuccessful) return FilmxyCookies()

    val userNonce =
        res.document.select("script").find { it.data().contains("var userNonce") }?.data()?.let {
            Regex("var\\suserNonce.*?\"(\\S+?)\";").find(it)?.groupValues?.get(1)
        }

    var phpsessid = session.baseClient.cookieJar.loadForRequest(url.toHttpUrl())
        .first { it.name == "PHPSESSID" }.value

    session.post(
        cookieUrl,
        data = mapOf(
            "action" to "guest_login",
            "nonce" to "$userNonce",
        ),
        headers = mapOf(
            "Cookie" to "PHPSESSID=$phpsessid; G_ENABLED_IDPS=google",
            "X-Requested-With" to "XMLHttpRequest",
        )
    )

    val cookieJar = session.baseClient.cookieJar.loadForRequest(cookieUrl.toHttpUrl())
    phpsessid = cookieJar.first { it.name == "PHPSESSID" }.value
    val wLog =
        cookieJar.first { it.name == "wordpress_logged_in_8bf9d5433ac88cc9a3a396d6b154cd01" }.value
    val wSec = cookieJar.first { it.name == "wordpress_sec_8bf9d5433ac88cc9a3a396d6b154cd01" }.value

    return FilmxyCookies(phpsessid, wLog, wSec)
}

fun String?.fixTitle(): String? {
    return this?.replace(Regex("[!%:]|( &)"), "")?.replace(" ", "-")?.lowercase()
        ?.replace("-–-", "-")
}

fun getLanguage(str: String): String {
    return if (str.contains("(in_ID)")) "Indonesian" else str
}

fun getKisskhTitle(str: String?): String? {
    return str?.replace(Regex("[^a-zA-Z0-9]"), "-")
}

fun getQuality(str: String): Int {
    return when (str) {
        "360p" -> Qualities.P240.value
        "480p" -> Qualities.P360.value
        "720p" -> Qualities.P480.value
        "1080p" -> Qualities.P720.value
        "1080p Ultra" -> Qualities.P1080.value
        else -> getQualityFromName(str)
    }
}

fun getGMoviesQuality(str: String): Int {
    return when {
        str.contains("480P", true) -> Qualities.P480.value
        str.contains("720P", true) -> Qualities.P720.value
        str.contains("1080", true) -> Qualities.P1080.value
        str.contains("4K", true) -> Qualities.P2160.value
        else -> Qualities.Unknown.value
    }
}

fun getBaseUrl(url: String): String {
    return URI(url).let {
        "${it.scheme}://${it.host}"
    }
}

fun decryptStreamUrl(data: String): String {

    fun getTrash(arr: List<String>, item: Int): List<String> {
        val trash = ArrayList<List<String>>()
        for (i in 1..item) {
            trash.add(arr)
        }
        return trash.reduce { acc, list ->
            val temp = ArrayList<String>()
            acc.forEach { ac ->
                list.forEach { li ->
                    temp.add(ac.plus(li))
                }
            }
            return@reduce temp
        }
    }

    val trashList = listOf("@", "#", "!", "^", "$")
    val trashSet = getTrash(trashList, 2) + getTrash(trashList, 3)
    var trashString = data.replace("#2", "").split("//_//").joinToString("")

    trashSet.forEach {
        val temp = base64Encode(it.toByteArray())
        trashString = trashString.replace(temp, "")
    }

    return base64Decode(trashString)

}

fun fixUrl(url: String, domain: String): String {
    if (url.startsWith("http")) {
        return url
    }
    if (url.isEmpty()) {
        return ""
    }

    val startsWithNoHttp = url.startsWith("//")
    if (startsWithNoHttp) {
        return "https:$url"
    } else {
        if (url.startsWith('/')) {
            return domain + url
        }
        return "$domain/$url"
    }
}

suspend fun loadLinksWithWebView(
    url: String,
    callback: (ExtractorLink) -> Unit
) {
    val foundVideo = WebViewResolver(
        Regex("""\.m3u8|i7njdjvszykaieynzsogaysdgb0hm8u1mzubmush4maopa4wde\.com""")
    ).resolveUsingWebView(
        requestCreator(
            "GET", url, referer = "https://olgply.com/"
        )
    ).first ?: return

    callback.invoke(
        ExtractorLink(
            "Olgply",
            "Olgply",
            foundVideo.url.toString(),
            "",
            Qualities.P1080.value,
            true
        )
    )
}