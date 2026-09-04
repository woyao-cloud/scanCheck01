package com.example.compliance.engineadapter.sonarqube

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/** SonarQube REST API 客户端（R-M15-D5/D7）：RestClient 构造器注入（测试可绑定 MockRestServiceServer）。
 *  ceTaskStatus → task.status 字符串；issues → 原始 JSON（componentKeys + resolved=false 未决 issue + ps=500 单页）。 */
@Component
class SonarQubeApiClient(
    private val restClient: RestClient = RestClient.create(),
) {
    private val objectMapper = ObjectMapper()

    fun ceTaskStatus(serverUrl: String, taskId: String, token: String): String {
        val body = restClient.get()
            .uri("$serverUrl/api/ce/task?id=$taskId")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .retrieve()
            .body(String::class.java) ?: "{}"
        return runCatching { objectMapper.readTree(body).path("task").path("status").asText("") }.getOrElse { "" }
    }

    fun issues(serverUrl: String, projectKey: String, token: String): String =
        restClient.get()
            .uri("$serverUrl/api/issues/search?componentKeys=$projectKey&resolved=false&ps=500")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
            .retrieve()
            .body(String::class.java) ?: "{}"
}
