package com.jotbill.app.recognition

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 支付交接栅栏指纹校正的回归测试。
 *
 * 真机 .tmp-device-capture-20260824-142754 中栅栏与候选指纹错位，
 * 导致快照被自己的栅栏永久锁死：
 *   交接栅栏已设置: fingerprint=0b64b3afe9ce, candidates=CHECKOUT_STABLE@1286a1e87671
 *   快照消费被交接栅栏拒绝: requested=1286a1e87671, barrier=0b64b3afe9ce
 * 该组合在日志中成对出现 6 次，后果是所有支付证据停在 WAITING_ENRICHMENT、
 * 无一笔自动入账（上一轮尚有 4 笔成功，本轮全面退步）。
 *
 * 根因是调用方传入结算会话建立时的观察指纹，而候选指纹是截图那一刻写入的；
 * 结算页指纹持续漂移，两者几乎必然不同。
 */
class HandoffFenceFingerprintTest {

    /**
     * 候选指纹与请求指纹不一致时，栅栏必须按候选校正。
     *
     * 这是本次修复的核心场景，直接对应真机的锁死现象。
     */
    @Test
    fun 指纹错位时按候选校正() {
        assertEquals(
            "栅栏必须落在候选实际持有的指纹上，否则快照永久锁死",
            "1286a1e87671",
            resolveHandoffFenceFingerprint(
                requestedFingerprint = "0b64b3afe9ce",
                candidateFingerprints = listOf("1286a1e87671")
            )
        )
    }

    /**
     * 请求指纹本就在候选中时应原样沿用。
     *
     * 这是正常路径，不能因校正逻辑而改变既有行为。
     */
    @Test
    fun 请求指纹命中候选时原样沿用() {
        assertEquals(
            "1286a1e87671",
            resolveHandoffFenceFingerprint(
                requestedFingerprint = "1286a1e87671",
                candidateFingerprints = listOf("aaaaaaaaaaaa", "1286a1e87671")
            )
        )
    }

    /**
     * 多候选且请求指纹不匹配时取最新候选。
     *
     * 候选列表按写入顺序排列，最后一个是最新快照，
     * 交接应当保护最新的那张图。
     */
    @Test
    fun 多候选不匹配时取最新候选() {
        assertEquals(
            "cccccccccccc",
            resolveHandoffFenceFingerprint(
                requestedFingerprint = "0b64b3afe9ce",
                candidateFingerprints = listOf("aaaaaaaaaaaa", "bbbbbbbbbbbb", "cccccccccccc")
            )
        )
    }

    /**
     * 无候选时保持请求指纹。
     *
     * 槽位为空时栅栏没有保护对象，此时不能凭空造出指纹，
     * 保持原值让后续消费按既有语义走"候选为空"分支。
     */
    @Test
    fun 无候选时保持请求指纹() {
        assertEquals(
            "0b64b3afe9ce",
            resolveHandoffFenceFingerprint(
                requestedFingerprint = "0b64b3afe9ce",
                candidateFingerprints = emptyList()
            )
        )
    }
}
