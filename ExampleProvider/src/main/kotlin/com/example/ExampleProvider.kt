package com.cloudstream.plugin.animehay // Anh có thể đổi tên gói plugin này nếu muốn

import com.lagradost.cloudstream3.* // Import tất cả các thư viện cần thiết từ CloudStream
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink // Cần cho loadLinks
import com.lagradost.cloudstream3.utils.ExtractorApi // Cần cho loadLinks và tự động xử lý iframe
import com.lagradost.cloudstream3.utils.ExtractorApi.Companion.getExtractorApi // Cần cho tự động xử lý iframe
import org.jsoup.Jsoup // Cần cho việc phân tích HTML
import org.jsoup.nodes.Document // Cần cho Jsoup
import org.jsoup.nodes.Element // Cần cho Jsoup
import java.net.URLEncoder // Cần để encode URL

// Đây là class chính của plugin, giống như "ngôi nhà" của extension này vậy đó!
@CloudstreamPlugin
class AnimeHayPlugin: CloudstreamPlugin() {
    override fun load(context: Plugin.PluginContext) {
        // Trong hàm load này, mình sẽ đăng ký cái nguồn phim của mình
        context.registerMainAPI(AnimeHayProvider())
        // Yuu Onii-chan có thể thêm settings ở đây sau này nếu muốn
    }
}

// Đây là class xử lý việc "cào" dữ liệu từ trang web
class AnimeHayProvider : MainAPI() {
    // === Thông tin cơ bản về nguồn phim ===
    override var mainUrl = "https://animehay.page" // Địa chỉ trang web chính
    override var name = "AnimeHay" // Tên hiển thị trong CloudStream
    // Hỗ trợ cả Movie (Special) và TvSeries (Anime bộ)
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "vi" // Ngôn ngữ là Tiếng Việt

    // Có thể thêm biểu tượng cho nguồn nếu có link ảnh
    // override val hasアイコン = true
    // override val preferredアイコン = R.drawable.icon_animehay // Cần thêm icon vào project nếu muốn dùng

    // === 1. Hàm Tìm kiếm (Search) ===
    // Sẽ được gọi khi người dùng tìm kiếm phim
    override suspend fun search(query: String): List<SearchResponse> {
        // Tạo URL tìm kiếm từ từ khóa của người dùng
        // Thay thế khoảng trắng bằng "-" và encode URL
        val searchUrl = "$mainUrl/tim-kiem/${query.replace(" ", "-").encode()}"

        // Tải nội dung trang tìm kiếm
        val document = app.get(searchUrl).document // app dùng để tải trang web

        // Tìm div chứa danh sách kết quả
        val moviesList = document.selectFirst("div.movies-list")

        // Nếu không tìm thấy, trả về danh sách rỗng
        if (moviesList == null) return emptyList()

        // Duyệt qua từng mục phim trong danh sách và tạo SearchResponse
        return moviesList.select("div.movie-item").mapNotNull { movieItem ->
            // Tìm thẻ <a> chứa link và tên phim
            val linkElement = movieItem.selectFirst("a") ?: return@mapNotNull null

            // Lấy link trang chi tiết
            val href = linkElement.attr("href")
            // Link từ trang này đã là full URL, không cần fixUrl ở đây

            // Lấy tên phim từ thuộc tính 'title'
            val title = linkElement.attr("title").trim()

            // Tìm thẻ <img> để lấy poster
            val posterElement = linkElement.selectFirst("img")
            val posterUrl = posterElement?.attr("src") // Lấy link poster

            if (title.isNotEmpty() && href.isNotEmpty()) {
                 newSearchResponse(
                    title, // Tên phim
                    href,  // Link đến trang chi tiết (sẽ dùng cho hàm load)
                    // Dựa vào HTML tìm kiếm, chưa phân biệt được Movie hay TvSeries
                    // Tạm để là TvSeries hoặc Movie, sẽ xác định lại trong hàm load
                    TvType.TvSeries // Hoặc TvType.Movie tùy Yuu Onii-chan muốn mặc định là gì
                ) {
                    this.posterUrl = posterUrl
                }
            } else null // Bỏ qua nếu không có tên hoặc link
        }
    }

    // === 2. Hàm Lấy thông tin chi tiết (Load) ===
    // Sẽ được gọi khi người dùng chọn một phim từ kết quả tìm kiếm
    override suspend fun load(url: String): LoadResponse? {
        // Tải nội dung trang chi tiết
        val document = app.get(url).document

        // Lấy Tên phim chính
        val title = document.selectFirst("h1.heading_movie")?.text()?.trim()
        if (title.isNullOrEmpty()) return null // Nếu không có tên thì coi như lỗi

        // Lấy Poster
        val posterUrl = document.selectFirst("div.head div.first img")?.attr("src")

        // Lấy Mô tả
        val description = document.selectFirst("div.desc p")?.text()?.trim()

        // Lấy Thể loại
        val genres = document.select("div.list_cate a").map { it.text().trim() }

        // Lấy Năm phát hành
        val year = document.selectFirst("div.update_time div:nth-child(2)")?.text()?.trim()?.toIntOrNull()

        // Lấy Điểm
        val rating = document.selectFirst("div.score div:nth-child(2)")?.text()?.trim()?.split("||")?.getOrNull(0)?.trim()?.toDoubleOrNull()

        // Lấy Trạng thái
        val statusText = document.selectFirst("div.status div:nth-child(2)")?.text()?.trim()
        // Có thể mapping statusText sang enum Status nếu cần
        // val status = when (statusText) { "Hoàn thành" -> Status.Completed "Đang chiếu" -> Status.Ongoing else -> null }

        // === Lấy danh sách tập phim / Link xem ===
        // Tìm các thẻ <a> trong div chứa danh sách tập
        val episodeLinks = document.select("div.list-item-episode a")

        val episodes = episodeLinks.mapNotNull { episodeLink ->
            val epName = episodeLink.text().trim()
            val epUrl = episodeLink.attr("href")

            if (epName.isNotEmpty() && epUrl.isNotEmpty()) {
                Episode(
                    epName, // Tên tập
                    epUrl // Link của tập (sẽ dùng cho loadLinks)
                )
            } else null // Bỏ qua nếu không lấy được tên hoặc link
        }

        // === Tạo đối tượng LoadResponse ===
        // Nếu có danh sách tập thì là TvSeries, ngược lại (hoặc chỉ 1 tập "Full") có thể coi là Movie hoặc TvSeries 1 tập
        // Với cấu trúc này, ta tạo TvSeriesLoadResponse nếu có bất kỳ tập nào được tìm thấy
        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries) { // TvType.TvSeries
                this.posterUrl = posterUrl
                this.plot = description
                this.tags = genres
                this.year = year
                this.rating = rating
                // this.status = status
                this.episodes = mapOf(1 to episodes) // Đặt tất cả vào Season 1
            }
        } else {
             // Trường hợp không tìm thấy tập nào cả (có thể là phim lẻ và link stream ở đâu đó khác,
             // hoặc có lỗi khi cào danh sách tập).
             // Với cấu trúc này, Mahiro nghĩ trường hợp này ít xảy ra vì "Tập Full" sẽ được tìm thấy.
             // Nếu là phim lẻ thực sự, anh cần tìm cách lấy link stream chính từ trang chi tiết
             // và truyền vào movieUrl = ... trong newMovieLoadResponse
             return newMovieLoadResponse(title, url, TvType.Movie, url) { // Ví dụ coi url trang chi tiết là movieUrl luôn (cần kiểm tra lại logic này)
                 this.posterUrl = posterUrl
                 this.plot = description
                 this.tags = genres
                 this.year = year
                 this.rating = rating
             }
        }

        // Trả về null nếu không thể load
        // return null // Dòng này có thể bỏ vì đã return trong if/else
    }

    // === 3. Hàm Tìm link xem (loadLinks) ===
    // Sẽ được gọi khi người dùng chọn một tập phim để xem
    override suspend fun loadLinks(
        data: String, // Đây là cái epUrl (link của tập phim) từ hàm load
        isCasting: Boolean,
        subs: List<SubtitleData>
    ): List<ExtractorLink> {
        val extractorLinks = mutableListOf<ExtractorLink>()

        // Tải lại trang xem phim (data chính là link của tập phim)
        val document = app.get(data).document

        // Tìm và phân tích script để lấy link M3U8
        document.select("script").forEach { script ->
            val scriptText = script.html()

            // Regex để tìm link M3U8 trong biến $info_play_video.tik
            // Cẩn thận với các ký tự đặc biệt trong regex
            val regex = "var \$info_play_video\\s*=\\s*\\{.*?tik:\\s*'(.*?)'".toRegex()
            val match = regex.find(scriptText)

            val m3u8Link = match?.groups?.get(1)?.value

            if (!m3u8Link.isNullOrEmpty()) {
                // Thêm link M3U8 vào danh sách
                extractorLinks.add(
                    ExtractorLink(
                        "AnimeHay TOK", // Tên Extractor (có thể dùng tên nguồn + server)
                        "AnimeHay TOK", // Tên chất lượng/server phụ
                        m3u8Link, // Link stream
                        "", // Referer (có thể cần đặt lại mainUrl hoặc url trang hiện tại tùy host)
                        Qualities.Unknown.value, // Chất lượng, có thể thử phân tích thêm nếu có
                        true // Đây là link M3u8
                    )
                )
                // Nếu chỉ cần link từ server TOK, có thể return ngay sau khi tìm thấy
                // return extractorLinks
            }
        }

        // Tìm các iframe và tạo ExtractorLink cho chúng
        document.select("iframe").forEach { iframe ->
            val iframeSrc = iframe.attr("src")

            if (iframeSrc.isNotEmpty()) {
                // Tạo ExtractorLink cho iframe. CloudStream sẽ tự động tìm extractor phù hợp.
                 extractorLinks.add(
                    ExtractorLink(
                       "AnimeHay Iframe", // Tên Extractor
                       "AnimeHay Iframe", // Tên chất lượng/server phụ
                       iframeSrc, // Link iframe
                       "", // Referer
                       Qualities.Unknown.value, // Chất lượng
                       false // Không phải M3u8 trực tiếp, là link iframe
                    )
                 )
            }
        }

        // Trả về danh sách tất cả các link tìm được (M3U8 và iframe)
        return extractorLinks
    }

    // === Các hàm hỗ trợ ===

    // Hàm encode URL
    private fun String.encode(): String {
        return try {
            URLEncoder.encode(this, "UTF-8")
        } catch (e: Exception) {
            e.printStackTrace()
            this // Trả về chuỗi gốc nếu lỗi
        }
    }

    // Hàm chuyển String sang Double, trả về null nếu lỗi
    private fun String.toDoubleOrNull(): Double? {
        return try {
            this.toDouble()
        } catch (e: NumberFormatException) {
            null
        }
    }

    // Hàm này có thể cần nếu các link trong HTML là tương đối (bắt đầu bằng / thay vì http)
    // Dựa vào HTML anh gửi thì các link có vẻ là full URL, nên hàm này có thể không cần thiết
    /*
    private fun fixUrl(url: String?): String? {
        if (url.isNullOrEmpty()) return null
        if (url.startsWith("http")) return url // Đã là full URL
        // Ghép với mainUrl nếu là tương đối
        return "$mainUrl$url"
    }
     */
}
