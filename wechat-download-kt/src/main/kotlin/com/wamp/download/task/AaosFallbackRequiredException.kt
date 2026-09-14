package com.wamp.download.task

/**
 * 抛出此异常表示当前的爬取通道已遇到如“滑块拦截”、“账号全封”、“覆盖严重断层”等
 * 无法通过无头爬虫逻辑克服的风控或业务死局。
 *
 * 抛出后任务将不再向 ATD 发送 `FAILED` 响应，而是直接向死信队列投递，
 * 供真机物理集群（AAOS）接管兜底。
 */
class AaosFallbackRequiredException(message: String) : RuntimeException(message)