package com.example.compliance.notification.application

/** Webhook 渠道 seam（spec R-M17-D5）：自定义接口，生产实现 RestWebhookClient（RestClient POST）。 */
interface WebhookClient {
    /** POST JSON body；成功返回 true。实现不得抛异常 —— 失败返回 false，由 sender 置 FAILED。 */
    fun post(url: String, body: String): Boolean
}
