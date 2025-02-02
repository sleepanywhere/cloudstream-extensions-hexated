package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.mvvm.suspendSafeApiCall
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class KuramanimeProvider : MainAPI() {
    override var mainUrl = "https://kuramanime.com"
    override var name = "Kuramanime"
    override val hasQuickSearch = false
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val jikanAPI = "https://api.jikan.moe/v4"

        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special")) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String): ShowStatus {
            return when (t) {
                "Selesai Tayang" -> ShowStatus.Completed
                "Sedang Tayang" -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/anime/ongoing?order_by=updated&page=" to "Sedang Tayang",
        "$mainUrl/anime/finished?order_by=updated&page=" to "Selesai Tayang",
        "$mainUrl/properties/season/summer-2022?order_by=most_viewed&page=" to "Dilihat Terbanyak Musim Ini",
        "$mainUrl/anime/movie?order_by=updated&page=" to "Film Layar Lebar",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + page).document

        val home = document.select("div.col-lg-4.col-md-6.col-sm-6").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun getProperAnimeLink(uri: String): String {
        return if (uri.contains("/episode")) {
            Regex("(.*)/episode/.+").find(uri)?.groupValues?.get(1).toString() + "/"
        } else {
            uri
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = getProperAnimeLink(fixUrl(this.selectFirst("a")!!.attr("href")))
        val title = this.selectFirst("h5 a")?.text() ?: return null
        val posterUrl = fixUrl(this.select("div.product__item__pic.set-bg").attr("data-setbg"))
        val episode = Regex("([0-9*])\\s?/").find(
            this.select("div.ep span").text()
        )?.groupValues?.getOrNull(1)?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        val link = "$mainUrl/anime?search=$query&order_by=latest"
        val document = app.get(link).document

        return document.select("div#animeList div.col-lg-4.col-md-6.col-sm-6").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst(".anime__details__title > h3")!!.text().trim()
        val poster = document.selectFirst(".anime__details__pic")?.attr("data-setbg")
        val tags = document.select("div.anime__details__widget > div > div:nth-child(2) > ul > li:nth-child(1)")
            .text().trim().replace("Genre: ", "").split(", ")

        val year = Regex("[^0-9]").replace(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(5)")
                .text().trim().replace("Musim: ", ""), ""
        ).toIntOrNull()
        val status = getStatus(
            document.select("div.anime__details__widget > div > div:nth-child(1) > ul > li:nth-child(3)")
                .text().trim().replace("Status: ", "")
        )
        val type = document.selectFirst("div.col-lg-6.col-md-6 ul li:contains(Tipe:) a")?.text()?.lowercase() ?: "tv"
        val description = document.select(".anime__details__text > p").text().trim()

        val malId = app.get("${jikanAPI}/anime?q=$title&start_date=${year}&type=$type&limit=1")
            .parsedSafe<JikanResponse>()?.data?.firstOrNull()?.mal_id
        val anilistId = app.post(
            "https://graphql.anilist.co/", data = mapOf(
                "query" to "{Media(idMal:$malId,type:ANIME){id}}",
            )
        ).parsedSafe<DataAni>()?.data?.media?.id

        val episodes =
            Jsoup.parse(document.select("#episodeLists").attr("data-content")).select("a").map {
                val name = it.text().trim()
                val link = it.attr("href")
                Episode(link, name)
            }

        val recommendations = document.select("div#randomList > a").mapNotNull {
            val epHref = it.attr("href")
            val epTitle = it.select("h5.sidebar-title-h5.px-2.py-2").text()
            val epPoster = it.select(".product__sidebar__view__item.set-bg").attr("data-setbg")

            newAnimeSearchResponse(epTitle, epHref, TvType.Anime) {
                this.posterUrl = epPoster
                addDubStatus(dubExist = false, subExist = true)
            }
        }

        return newAnimeLoadResponse(title, url, getType(type)) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            addMalId(malId?.toIntOrNull())
            addAniListId(anilistId?.toIntOrNull())
            this.tags = tags
            this.recommendations = recommendations
        }

    }

    private suspend fun invokeLocalSource(
        url: String,
        ref: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(
            url,
            referer = ref,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).document
        document.select("video#player > source").map {
            val link = fixUrl(it.attr("src"))
            val quality = it.attr("size").toIntOrNull()
            callback.invoke(
                ExtractorLink(
                    name,
                    name,
                    link,
                    referer = "$mainUrl/",
                    quality = quality ?: Qualities.Unknown.value,
                    headers = mapOf(
                        "Range" to "bytes=0-"
                    )
                )
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(data).document
        res.select("select#changeServer option").apmap { source ->
            safeApiCall {
                val server = source.attr("value")
                val link = "$data?activate_stream=1&stream_server=$server"
                if (server == "kuramadrive") {
                    invokeLocalSource(link, data, callback)
                } else {
                    app.get(
                        link,
                        referer = data,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).document.select("div.iframe-container iframe").attr("src").let { videoUrl ->
                        loadExtractor(fixUrl(videoUrl), "$mainUrl/", subtitleCallback, callback)
                    }
                }
            }
        }

        return true
    }

    data class Data(
        @JsonProperty("mal_id") val mal_id: String? = null,
    )

    data class JikanResponse(
        @JsonProperty("data") val data: ArrayList<Data>? = arrayListOf(),
    )

    private data class IdAni(
        @JsonProperty("id") val id: String? = null,
    )

    private data class MediaAni(
        @JsonProperty("Media") val media: IdAni? = null,
    )

    private data class DataAni(
        @JsonProperty("data") val data: MediaAni? = null,
    )

}