package com.mytv.cn.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningContractTest {
    @Test
    fun ordinaryABogusUsesUndefinedUserAgentOverride() {
        val script = buildABogusScript("aid=6383", "", RECOMMEND_PATH, null)

        assertTrue(script.contains("/aweme/v1/web/tab/feed/"))
        assertTrue(script.contains(",undefined)"))
        assertFalse(script.contains("sessionid"))
    }

    @Test
    fun liveABogusCarriesOnlyExplicitUserAgentOverride() {
        val script = buildABogusScript("aid=6383", "", "/webcast/feed/", ABogusSigner.LIVE_USER_AGENT)

        assertTrue(script.contains("QQBrowser/19.7.6764.400"))
        assertFalse(script.contains("undefined"))
    }

    @Test
    fun recommendationIsTheUnsignedWebFeed() {
        assertFalse(shouldSignWebRequest(RECOMMEND_PATH))
        assertTrue(shouldSignWebRequest("/aweme/v1/web/aweme/detail/"))
    }

    @Test
    fun queryEncodingKeepsEmptyParametersInInsertionOrder() {
        val query = encodeWebQuery(linkedMapOf("aid" to "6383", "tag_id" to "", "keyword" to "猫 狗"))

        assertEquals("aid=6383&tag_id=&keyword=%E7%8C%AB+%E7%8B%97", query)
    }

    @Test
    fun qrSignerTargetsUseFullPassportUrls() {
        assertEquals("https://login.douyin.com/passport/web/get_qrcode/", GET_QR_URL)
        assertEquals("https://login.douyin.com/passport/web/check_qrconnect/", CHECK_QR_URL)
    }
}
