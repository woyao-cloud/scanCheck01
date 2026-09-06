package com.example.compliance.notification.application

import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

/** 生产 Webhook 客户端：RestClient POST（spring-boot-starter-web 自动配置 RestClient.Builder）。 */
@Service
class RestWebhookClient(private val builder: RestClient.Builder) : WebhookClient {
    override fun post(url: String, body: String): Boolean =
        runCatching {
            builder.build().post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toBodilessEntity()
            true
        }.getOrDefault(false)
}
