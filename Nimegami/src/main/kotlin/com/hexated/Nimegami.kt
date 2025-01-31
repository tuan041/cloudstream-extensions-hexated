package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.URI

class Nimegami : MainAPI() {
    override var mainUrl = "https://nimegami.id"
    override var name = "Nimegami"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        fun getType(t: String): TvType {
            return if (t.contains("OVA", true) || t.contains("Special", true)) TvType.OVA
            else if (t.contains("Movie", true)) TvType.AnimeMovie
            else TvType.Anime
        }

        fun getStatus(t: String?): ShowStatus {
            return when {
                t?.contains("On-Going", true) == true -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    override val mainPage = mainPageOf(
        "" to "Updated Anime",
        "/type/tv" to "Anime",
        "/type/movie" to "Movie",
        "/type/ona" to "ONA",
        "/type/live-action" to "Live Action",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl${request.data}/page/$page").document
        val home = document.select("div.post-article article, div.archive article").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = request.name != "Updated Anime"
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val title = this.selectFirst("h2 a")?.text() ?: return null
        val posterUrl = (this.selectFirst("noscript img") ?: this.selectFirst("img"))?.attr("src")
        val episode = this.selectFirst("ul li:contains(Episode), div.eps-archive")?.ownText()
            ?.filter { it.isDigit() }?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }

    }

    override suspend fun search(query: String): List<SearchResponse> {
        return app.get("$mainUrl/?s=$query&post_type=post").document.select("div.archive article")
            .mapNotNull {
                it.toSearchResult()
            }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val table = document.select("div#Info table tbody")
        val title = table.getContent("Judul :").text()
        val poster = document.selectFirst("div.coverthumbnail img")?.attr("src")
        val bgPoster = document.selectFirst("div.thumbnail-a img")?.attr("src")
        val tags = table.getContent("Kategori").select("a").map { it.text() }

        val year = table.getContent("Musim / Rilis").text().filter { it.isDigit() }.toIntOrNull()
        val status = getStatus(document.selectFirst("h1[itemprop=headline]")?.text())
        val type = table.getContent("Type").text()
        val description = document.select("div#Sinopsis p").text().trim()


        val episodes = document.select("div.list_eps_stream li")
            .mapNotNull {
                val name = it.text()
                val link = it.attr("data")
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
            backgroundPosterUrl = bgPoster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = description
            this.tags = tags
            this.recommendations = recommendations
        }

    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        tryParseJson<ArrayList<Sources>>(base64Decode(data))?.map { sources ->
            sources.url?.apmap { url ->
                loadFixedExtractor(url.fixIframe(), sources.format, "$mainUrl/", subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun loadFixedExtractor(
        url: String,
        quality: String?,
        referer: String? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            callback.invoke(
                ExtractorLink(
                    link.name,
                    link.name,
                    link.url,
                    link.referer,
                    getQualityFromName(quality),
                    link.isM3u8,
                    link.headers,
                    link.extractorData
                )
            )
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    }

    private fun Elements.getContent(css: String) : Elements {
        return this.select("tr:contains($css) td:last-child")
    }

    private fun String.fixIframe() : String {
        val url = base64Decode(this.substringAfter("url=").substringAfter("id="))
        val host = getBaseUrl(url)
        return when {
            url.contains("hxfile") -> {
                val id = url.substringAfterLast("/")
                "$host/embed-$id.html"
            }
            else -> fixUrl(url)
        }
    }

    data class Sources(
        @JsonProperty("format") val format: String? = null,
        @JsonProperty("url") val url: ArrayList<String>? = arrayListOf(),
    )

}
