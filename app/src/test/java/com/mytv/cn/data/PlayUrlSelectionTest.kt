package com.mytv.cn.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlayUrlSelectionTest {
    private val signedUrl =
        "https://www.douyinvod.com/3a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d/65432101/video/tos/cn/tos-cn-ve-15/video.mp4"
    private val apiPlayUrl = "https://www.douyin.com/aweme/v1/play/?video_id=v0d00fg10000&ratio=1080p&line=0"
    private val signatureUrl = "https://www.douyinvod.com/video/tos/cn/tos-cn-ve-15/video.mp4?signature=abc123"
    private val plainUrl = "https://www.douyinvod.com/video/tos/cn/tos-cn-ve-15/video.mp4"

    @Test
    fun prefersSignedDouyinvodPath() {
        assertEquals(signedUrl, DouyinJson.selectBestPlayUrl(listOf(plainUrl, apiPlayUrl, signedUrl)))
    }

    @Test
    fun fallsBackToApiPlayUrlThenSignatureThenLast() {
        assertEquals(apiPlayUrl, DouyinJson.selectBestPlayUrl(listOf(plainUrl, apiPlayUrl, signatureUrl)))
        assertEquals(signatureUrl, DouyinJson.selectBestPlayUrl(listOf(plainUrl, signatureUrl)))
        assertEquals(plainUrl, DouyinJson.selectBestPlayUrl(listOf(apiPlayUrl.replace("douyin.com", "iesdouyin.com"), plainUrl)))
    }

    @Test
    fun autoResolutionUsesPrimaryPlayAddrCandidates() {
        val item = VideoItem(
            id = "1",
            playAddrUrls = listOf(apiPlayUrl, signedUrl),
            playAddr265Urls = listOf("https://www.douyin.com/aweme/v1/play/?video_id=h265"),
            sources = listOf(
                VideoSource(url = plainUrl, quality = "高清 1080P", codec = "h264", width = 1920, height = 1080, urls = listOf(plainUrl))
            )
        )
        val settings = AppSettings()
        val source = DouyinJson.selectVideoSource(item, settings)

        assertNotNull(source)
        assertEquals(signedUrl, source!!.url)
    }

    @Test
    fun highestResolutionPrefersLargestGear() {
        val item = VideoItem(
            id = "2",
            playAddrUrls = listOf(plainUrl),
            sources = listOf(
                VideoSource(url = plainUrl, quality = "流畅 540P", codec = "h264", width = 540, height = 960, urls = listOf(plainUrl)),
                VideoSource(url = signedUrl, quality = "高清 1080P", codec = "h264", width = 1080, height = 1920, urls = listOf(signedUrl))
            )
        )
        val source = DouyinJson.selectVideoSource(item, AppSettings(videoResolution = VideoResolution.HIGHEST))

        assertNotNull(source)
        assertEquals(signedUrl, source!!.url)
    }
}
