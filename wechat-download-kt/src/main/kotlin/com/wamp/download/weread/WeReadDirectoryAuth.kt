package com.wamp.download.weread

interface WeReadDirectoryAuth {
    fun vid(): String
    fun headers(): Map<String, String>
}

class WebDirectoryAuth(
    private val credentials: WeReadWebCredentials,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : WeReadDirectoryAuth {
    override fun vid(): String = credentials.wrVid

    override fun headers(): Map<String, String> = linkedMapOf(
        "x-vid" to credentials.wrVid,
        "x-skey" to credentials.wrSkey,
        "Cookie" to credentials.toCookieString(),
        "Origin" to "https://weread.qq.com",
        "Referer" to "https://weread.qq.com/",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "User-Agent" to userAgent,
        "Content-Type" to "application/json; charset=UTF-8",
    )

    companion object {
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
