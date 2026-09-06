package com.example.compliance.notification.application

import com.example.compliance.notification.domain.Notification
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Instant

/** 投递失败策略（R-M18-6 单一策略点）：指数退避 2^(retryCount-1) 分钟 + 最大次数上限。 */
@Component
class NotificationRetryBackoff(
    @Value("\${compliance.notification.retry.max-attempts:5}") val maxAttempts: Int,
) {
    /** sender 失败路径唯一落点：置 FAILED + retryCount+1 + errorMessage，按退避设 next_retry_at（达上限则不再重试）。 */
    fun onFailure(row: Notification, message: String?) {
        row.status = "FAILED"
        row.retryCount += 1
        row.errorMessage = message?.take(500)
        if (row.retryCount < maxAttempts) {
            row.nextRetryAt = Instant.now().plusSeconds(60L * exp2(row.retryCount - 1))
        } else {
            row.nextRetryAt = null
            row.errorMessage = (row.errorMessage ?: "") + "; max attempts reached"
        }
    }

    /** 2^n（retryCount=1 → 60s，2 → 120s，3 → 240s …），指数退避。 */
    private fun exp2(n: Int): Long = (1L shl n)
}
