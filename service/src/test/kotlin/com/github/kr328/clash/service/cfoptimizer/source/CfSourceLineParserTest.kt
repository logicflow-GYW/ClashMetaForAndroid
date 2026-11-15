package com.github.kr328.clash.service.cfoptimizer.source

import com.github.kr328.clash.service.cfoptimizer.CandidateIp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 源文本行 / Worker 行的解析口径（纯逻辑）。
 *
 * 重点锁住三件事：
 *  1. IPv6 有入口（方括号 + 裸地址默认 443）—— 引擎、探测、Worker 行格式早就支持 v6，
 *     此前只有解析层不认，整条 v6 通道是死的；
 *  2. 地址判据与引擎一致（private / loopback / ULA / 非 CF 端口一律不认），不做第二套规则；
 *  3. last-known-good 的 `[v6]:port#CC` 能被还原，不会被静默丢掉。
 */
class CfSourceLineParserTest {
    @Test
    fun ipv4WithPortIsAccepted() {
        val parsed = CfSourceLineParser.parseCandidateLine("104.16.1.2:2053")

        assertNotNull(parsed)
        assertEquals("104.16.1.2", parsed!!.address)
        assertEquals(2053, parsed.port)
    }

    @Test
    fun ipv4OutsideCfPortWhitelistIsRejected() {
        assertNull(CfSourceLineParser.parseCandidateLine("104.16.1.2:12345"))
    }

    @Test
    fun privateIpv4IsRejected() {
        assertNull(CfSourceLineParser.parseCandidateLine("192.168.1.1:443"))
        assertNull(CfSourceLineParser.parseCandidateLine("10.0.0.1:443"))
    }

    @Test
    fun bracketedIpv6WithPortIsAccepted() {
        val parsed = CfSourceLineParser.parseCandidateLine("[2403:18c0:4:5fd:be24:11ff:fe74:78ba]:8443")

        assertNotNull(parsed)
        assertEquals("2403:18c0:4:5fd:be24:11ff:fe74:78ba", parsed!!.address)
        assertEquals(8443, parsed.port)
    }

    @Test
    fun bareIpv6DefaultsToPort443() {
        val parsed = CfSourceLineParser.parseCandidateLine("2403:18c0:4:5fd:be24:11ff:fe74:78ba")

        assertNotNull(parsed)
        assertEquals(443, parsed!!.port)
    }

    @Test
    fun compressedIpv6IsNormalisedToFullForm() {
        val parsed = CfSourceLineParser.parseCandidateLine("[2403:18c0:4:5fd::1]:443")

        assertNotNull(parsed)
        // 规范化形式来自 Inet6Address.hostAddress —— 去重（/48）与 Worker 行都依赖它是完整形式。
        assertEquals("2403:18c0:4:5fd:0:0:0:1", parsed!!.address)
    }

    @Test
    fun loopbackAndUlaIpv6AreRejected() {
        assertNull(CfSourceLineParser.parseCandidateLine("[::1]:443"))
        assertNull(CfSourceLineParser.parseCandidateLine("[fd00::1]:443"))
        assertNull(CfSourceLineParser.parseCandidateLine("fe80::1"))
    }

    @Test
    fun garbageLinesAreRejected() {
        assertNull(CfSourceLineParser.parseCandidateLine(""))
        assertNull(CfSourceLineParser.parseCandidateLine("not-an-ip:443"))
        assertNull(CfSourceLineParser.parseCandidateLine("[2403:18c0:4::1]:99999"))
        assertNull(CfSourceLineParser.parseCandidateLine("104.16.1.2"))
    }

    @Test
    fun workerLineParsesIpv4() {
        val parsed = CfSourceLineParser.parseWorkerLine("104.16.1.2:443#JP")

        assertNotNull(parsed)
        assertEquals("104.16.1.2" to 443, parsed)
    }

    @Test
    fun workerLineParsesIpv6() {
        val parsed = CfSourceLineParser.parseWorkerLine("[2403:18c0:4:5fd::1]:8443#HK")

        assertNotNull(parsed)
        assertEquals("2403:18c0:4:5fd:0:0:0:1", parsed!!.first)
        assertEquals(8443, parsed.second)
    }

    @Test
    fun workerLineIgnoresGarbage() {
        assertNull(CfSourceLineParser.parseWorkerLine(""))
        assertNull(CfSourceLineParser.parseWorkerLine("#JP"))
        assertNull(CfSourceLineParser.parseWorkerLine("example.com:443#JP"))
    }

    @Test
    fun candidateSourceUsesTheSameParser() {
        // 入口一致性：源拉取路径与 last-known-good 路径共用同一个解析器（不做第二套规则）。
        val fromSource: CandidateIp? = CfSourceLineParser.parseCandidateLine("2403:18c0:4:5fd::1", "remote")
        assertEquals("2403:18c0:4:5fd:0:0:0:1", fromSource?.address)
        assertEquals(443, fromSource?.port)
    }
}
