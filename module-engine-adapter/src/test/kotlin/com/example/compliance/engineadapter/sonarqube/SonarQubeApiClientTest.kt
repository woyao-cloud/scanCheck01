package com.example.compliance.engineadapter.sonarqube

import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestTemplate
import kotlin.test.assertEquals

/** MockRestServiceServer（spring-test）绑定 RestTemplate → RestClient（R-M15-D7）：Bearer token + URI 逐字断言。 */
class SonarQubeApiClientTest {
    private val restTemplate = RestTemplate()
    private val mockServer = MockRestServiceServer.bindTo(restTemplate).build()
    private val client = SonarQubeApiClient(RestClient.builder(restTemplate).build())

    @Test
    fun `ce task status parses status from response`() {
        mockServer.expect(requestTo("http://sq:9000/api/ce/task?id=AX1"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
            .andRespond(withSuccess("""{"task":{"status":"SUCCESS"}}""", MediaType.APPLICATION_JSON))
        assertEquals("SUCCESS", client.ceTaskStatus("http://sq:9000", "AX1", "tok"))
        mockServer.verify()
    }

    @Test
    fun `ce task in progress`() {
        mockServer.expect(requestTo("http://sq:9000/api/ce/task?id=AX2"))
            .andRespond(withSuccess("""{"task":{"status":"IN_PROGRESS"}}""", MediaType.APPLICATION_JSON))
        assertEquals("IN_PROGRESS", client.ceTaskStatus("http://sq:9000", "AX2", "tok"))
        mockServer.verify()
    }

    @Test
    fun `issues returns raw json body`() {
        val issuesJson = """{"total":1,"issues":[]}"""
        mockServer.expect(requestTo("http://sq:9000/api/issues/search?componentKeys=m15app&resolved=false&ps=500"))
            .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer tok"))
            .andRespond(withSuccess(issuesJson, MediaType.APPLICATION_JSON))
        assertEquals(issuesJson, client.issues("http://sq:9000", "m15app", "tok"))
        mockServer.verify()
    }
}
