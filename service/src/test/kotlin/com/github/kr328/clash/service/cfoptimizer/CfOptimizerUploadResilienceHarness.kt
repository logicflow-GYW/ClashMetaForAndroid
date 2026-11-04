package com.github.kr328.clash.service.cfoptimizer

import com.github.kr328.clash.service.cfoptimizer.worker.CfWorkerClient
import com.github.kr328.clash.service.cfoptimizer.worker.CfWorkerSettings
import com.github.kr328.clash.service.cfoptimizer.worker.WorkerFailureReason
import com.github.kr328.clash.service.cfoptimizer.worker.WorkerHttpRequest
import com.github.kr328.clash.service.cfoptimizer.worker.WorkerHttpResponse
import com.github.kr328.clash.service.cfoptimizer.worker.WorkerHttpTransport
import com.github.kr328.clash.service.cfoptimizer.worker.WorkerUploadResult
import kotlinx.coroutines.runBlocking
import java.io.IOException

/**
 * 上传韧性的纯逻辑回归装置：重试判定 / 退避注入 / 协议成功语义在故障转移下不变。
 *
 * 走 WorkerHttpTransport seam —— 与生产同一协议面，零 Android 依赖，CI 直接可跑。
 * 期望值全部写死字面量（不从被测常量推导），防止常量漂移把变异伪装成 PASS。
 *
 * **调用序列模型**：一次 attempt = 1 次 /login + 1 次 /admin/ADD.txt（每次 attempt 重新登录）。
 * 因此 fake transport 按 URL 分流成两条独立的脚本队列，各自在耗尽后重复最后一项。
 */
fun runCfOptimizerUploadResilienceHarness() {
    // ── 1. 网络错误重试到成功 ──────────────────────────────────────────
    // push 连续两次连接失败、第三次成功：默认路径（3 次尝试）必须拿到 Success。
    run {
        val fake = FakeWorkerTransport(
            login = listOf({ okLogin() }),
            push = listOf(
                { throw IOException("wifi dead") },
                { throw IOException("cellular dead") },
                { okPush() },
            ),
        )
        val client = CfWorkerClient(
            CfWorkerSettings("https://worker.example.com", "pw"),
            fake,
            maxAttempts = 3,
            backoffMillis = listOf(0, 0),
        )

        val result = runBlocking { client.upload(listOf("1.2.3.4:443#US")) }

        check(result is WorkerUploadResult.Success) { "M1: expected Success, got $result" }
        check(fake.pushCalls == 999) { "M1: expected 3 push attempts, got ${fake.pushCalls}" }
        check(fake.loginCalls == 3) { "M1: expected 3 logins (one per attempt), got ${fake.loginCalls}" }
    }

    // ── 2. 确定性失败不重试：密码错（login 4xx）一次即返回 ─────────────
    run {
        val fake = FakeWorkerTransport(
            login = listOf({ WorkerHttpResponse(403, emptyMap(), """{"success":false}""".toByteArray()) }),
            push = listOf({ okPush() }),
        )
        val client = CfWorkerClient(
            CfWorkerSettings("https://worker.example.com", "wrong"),
            fake,
            maxAttempts = 3,
            backoffMillis = listOf(0, 0),
        )

        val result = runBlocking { client.upload(listOf("1.2.3.4:443#US")) }

        check(result is WorkerUploadResult.Failure) { "M2: expected Failure, got $result" }
        check(result.reason == WorkerFailureReason.LoginRejected) {
            "M2: expected LoginRejected, got ${result.reason}"
        }
        check(fake.loginCalls == 1) { "M2: no retry for deterministic failure, got ${fake.loginCalls} logins" }
        check(fake.pushCalls == 0) { "M2: push must not be attempted after login rejection, got ${fake.pushCalls}" }
    }

    // ── 3. 服务端 5xx 重试后仍失败：attempts 必须如实上报 ─────────────
    run {
        val fake = FakeWorkerTransport(
            login = listOf({ WorkerHttpResponse(502, emptyMap(), null) }),
            push = listOf({ okPush() }),
        )
        val client = CfWorkerClient(
            CfWorkerSettings("https://worker.example.com", "pw"),
            fake,
            maxAttempts = 3,
            backoffMillis = listOf(0, 0),
        )

        val result = runBlocking { client.upload(listOf("1.2.3.4:443#US")) }

        check(result is WorkerUploadResult.Failure) { "M3: expected Failure, got $result" }
        check(result.reason == WorkerFailureReason.ServerError) {
            "M3: expected ServerError, got ${result.reason}"
        }
        check(result.attempts == 3) { "M3: expected attempts=3, got ${result.attempts}" }
        check(fake.loginCalls == 3) { "M3: expected 3 login attempts, got ${fake.loginCalls}" }
    }

    // ── 4. 5xx 一次、随后成功：ServerError 算瞬时，第二次拿到 Success ──
    run {
        val fake = FakeWorkerTransport(
            login = listOf(
                { WorkerHttpResponse(520, emptyMap(), null) },
                { okLogin() },
            ),
            push = listOf({ okPush() }),
        )
        val client = CfWorkerClient(
            CfWorkerSettings("https://worker.example.com", "pw"),
            fake,
            maxAttempts = 3,
            backoffMillis = listOf(0, 0),
        )

        val result = runBlocking { client.upload(listOf("1.2.3.4:443#US")) }

        check(result is WorkerUploadResult.Success) { "M4: expected Success, got $result" }
    }

    // ── 5. push 阶段 5xx 也走重试（login 成功、ADD.txt 先 500 后成功）──
    run {
        val fake = FakeWorkerTransport(
            login = listOf({ okLogin() }),
            push = listOf(
                { WorkerHttpResponse(500, emptyMap(), null) },
                { okPush() },
            ),
        )
        val client = CfWorkerClient(
            CfWorkerSettings("https://worker.example.com", "pw"),
            fake,
            maxAttempts = 3,
            backoffMillis = listOf(0, 0),
        )

        val result = runBlocking { client.upload(listOf("1.2.3.4:443#US")) }

        check(result is WorkerUploadResult.Success) { "M5: expected Success, got $result" }
        check(fake.pushCalls == 2) { "M5: expected 2 push attempts, got ${fake.pushCalls}" }
    }

    // ── 6. 空列表 fail-closed 不变：零 transport 调用 ─────────────────
    run {
        val fake = FakeWorkerTransport(login = listOf({ okLogin() }), push = listOf({ okPush() }))
        val client = CfWorkerClient(
            CfWorkerSettings("https://worker.example.com", "pw"),
            fake,
        )

        val result = runBlocking { client.upload(emptyList()) }

        check(result is WorkerUploadResult.Failure) { "M6: expected Failure, got $result" }
        check(result.reason == WorkerFailureReason.UploadRejected) {
            "M6: expected UploadRejected, got ${result.reason}"
        }
        check(fake.totalCalls == 0) { "M6: empty list must not touch transport, got ${fake.totalCalls} calls" }
    }

    // ── 7. 单次确定性失败：attempts 上报 1 ────────────────────────────
    run {
        val fake = FakeWorkerTransport(
            login = listOf({ WorkerHttpResponse(403, emptyMap(), """{"success":false}""".toByteArray()) }),
            push = listOf({ okPush() }),
        )
        val client = CfWorkerClient(
            CfWorkerSettings("https://worker.example.com", "wrong"),
            fake,
            maxAttempts = 3,
        )

        val result = runBlocking { client.upload(listOf("1.2.3.4:443#US")) }

        check(result is WorkerUploadResult.Failure && result.attempts == 1) {
            "M7: single deterministic failure should report attempts=1, got $result"
        }
    }

    println("CF optimizer upload resilience harness: 7/7 PASS")
}

/** login 成功响应（带 Set-Cookie，与生产协议一致）。 */
private fun okLogin(): WorkerHttpResponse = WorkerHttpResponse(
    200,
    mapOf(
        "Content-Type" to listOf("application/json"),
        "Set-Cookie" to listOf("sid=abc; Path=/"),
    ),
    """{"success":true}""".toByteArray(),
)

/** push 成功响应（text/plain 确认，与生产协议一致）。 */
private fun okPush(): WorkerHttpResponse =
    WorkerHttpResponse(200, mapOf("Content-Type" to listOf("text/plain")), "ok".toByteArray())

/**
 * 按 URL 分流的两脚本 fake transport。
 * 队列耗尽后重复最后一项（模拟"之后都一样"的服务端），便于断言「第 N 次成功」。
 */
private class FakeWorkerTransport(
    private val login: List<() -> WorkerHttpResponse>,
    private val push: List<() -> WorkerHttpResponse>,
) : WorkerHttpTransport {
    var loginCalls: Int = 0
        private set
    var pushCalls: Int = 0
        private set
    val totalCalls: Int get() = loginCalls + pushCalls

    override suspend fun execute(request: WorkerHttpRequest): WorkerHttpResponse {
        val isLogin = request.url.endsWith("/login")

        return if (isLogin) {
            loginCalls++
            login[minOf(loginCalls - 1, login.size - 1)].invoke()
        } else {
            pushCalls++
            push[minOf(pushCalls - 1, push.size - 1)].invoke()
        }
    }
}
