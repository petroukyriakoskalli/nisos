package app.nisos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * Balances.
 *
 * Two things need pinning down here and they are both about honesty rather
 * than arithmetic:
 *
 * 1. **A source that cannot answer must not take the total with it.** One
 *    unreachable bank turning "you have €12,000" into "that didn't work" for
 *    three reachable accounts is the worst outcome available.
 * 2. **A partial total must not sound like a complete one.** Three of four is
 *    a different answer from three of three, and the number is the whole point
 *    of the question.
 *
 * The SMS parsing gets its own attention because the failure mode is a
 * confidently wrong number, which is worse than no number at all.
 */
class MoneyTest {

    private val now = LocalDateTime.of(2026, 8, 10, 12, 0)

    private fun source(id: String, amount: Double?, currency: String = "EUR",
                       asOf: LocalDateTime? = null) = object : MoneySource {
        override val id = id
        override val label = id
        override fun read() = amount?.let { Balance(it, currency, asOf) }
    }

    private fun exploding(id: String) = object : MoneySource {
        override val id = id
        override val label = id
        override fun read(): Balance = throw RuntimeException("bank is down")
    }

    // -- adding up ----------------------------------------------------------

    @Test fun `sums what answered`() {
        val wealth = total(listOf(source("a", 1000.0), source("b", 2500.50)), now)
        assertEquals(3500.50, wealth.primaryAmount, 0.001)
        assertEquals(2, wealth.answered)
        assertEquals(2, wealth.asked)
    }

    /** One unreachable bank must not cost you the other three. */
    @Test fun `a source that cannot answer is skipped not fatal`() {
        val wealth = total(listOf(source("a", 1000.0), source("b", null), source("c", 500.0)), now)
        assertEquals(1500.0, wealth.primaryAmount, 0.001)
        assertEquals(2, wealth.answered)
        assertEquals(3, wealth.asked)
    }

    /** Nor must one that throws -- the contract says read() shouldn't, but a
     *  contract is not an enforcement mechanism. */
    @Test fun `a source that throws is skipped too`() {
        val wealth = total(listOf(source("a", 1000.0), exploding("b")), now)
        assertEquals(1000.0, wealth.primaryAmount, 0.001)
        assertEquals(1, wealth.answered)
        assertEquals(2, wealth.asked)
    }

    /**
     * Converting needs a live rate this app does not have and should not
     * guess, so currencies stay apart. A wrong single number would be worse
     * than an honest two-part answer.
     */
    @Test fun `currencies are not silently merged`() {
        val wealth = total(listOf(source("a", 1000.0), source("b", 800.0, "GBP")), now)
        assertEquals("EUR", wealth.primary)
        assertEquals(1000.0, wealth.primaryAmount, 0.001)
        assertEquals(listOf("GBP"), wealth.others)
    }

    @Test fun `with no euros the largest currency leads`() {
        val wealth = total(listOf(source("a", 800.0, "GBP"), source("b", 200.0, "USD")), now)
        assertEquals("GBP", wealth.primary)
    }

    @Test fun `the oldest reading is reported`() {
        val old = now.minusDays(3)
        val wealth = total(
            listOf(source("a", 100.0, asOf = now), source("b", 100.0, asOf = old)), now
        )
        assertEquals(old, wealth.stalest)
    }

    @Test fun `no sources is an empty total not a crash`() {
        val wealth = total(emptyList(), now)
        assertEquals(0, wealth.answered)
        assertEquals(0.0, wealth.primaryAmount, 0.001)
    }

    // -- the spoken answer --------------------------------------------------

    private class Wallet(private val sources: List<MoneySource>) : Phone {
        override fun torch(on: Boolean) = Unit
        override fun battery() = 50 to "full"
        override fun startTimer(minutes: Int) = Unit
        override fun sendSms(to: String, body: String) = Unit
        override fun copyToClipboard(text: String) = Unit
        override fun doNotDisturb(on: Boolean) = Unit
        override fun setVolume(level: Int) = Unit
        override fun nextEvent(): CalendarEntry? = null
        override fun addEvent(summary: String, start: LocalDateTime, minutes: Int) = Unit
        override fun openWhatsApp(number: String, body: String) = Unit
        override fun lookupNumber(name: String): String? = null
        override fun now(): LocalDateTime = LocalDateTime.of(2026, 8, 10, 12, 0)
        override fun moneySources() = sources
        val written = mutableMapOf<String, Double>()
        override fun setBalance(account: String, amount: Double) { written[account] = amount }
        override fun remember(key: String, value: String) = Unit
        override fun recall(key: String): String? = null
        override fun forget(key: String) = false
        override fun memoryCounts() = 0 to 0
    }

    @Test fun `a complete total is just the number`() {
        val phone = Wallet(listOf(source("a", 12000.0), source("b", 340.0)))
        val (key, fields) = execute("money.total", emptyMap(), phone)
        assertEquals("money.total", key)
        assertEquals("12,340", fields["amount"])
        assertEquals("", fields["note"])
        assertEquals("12,340 EUR.", say("money.total", "en", fields))
    }

    /** The failure that matters: an account gone quiet must not be silent. */
    @Test fun `a partial total says so out loud`() {
        val phone = Wallet(listOf(source("a", 12000.0), source("b", null)))
        val (_, fields) = execute("money.total", emptyMap(), phone)
        assertTrue(fields["note"].toString(), fields["note"].toString().contains("1 of 2"))
        assertTrue(say("money.total", "el", fields).contains("1 of 2"))
    }

    @Test fun `another currency is mentioned rather than converted`() {
        val phone = Wallet(listOf(source("a", 12000.0), source("b", 900.0, "GBP")))
        val (_, fields) = execute("money.total", emptyMap(), phone)
        assertTrue(fields["note"].toString().contains("GBP"))
    }

    @Test fun `a stale reading is dated`() {
        val old = LocalDateTime.of(2026, 8, 3, 9, 0)
        val phone = Wallet(listOf(source("a", 500.0, asOf = old)))
        val (_, fields) = execute("money.total", emptyMap(), phone)
        assertTrue(fields["note"].toString(), fields["note"].toString().contains("3/8"))
    }

    /** Nothing configured is not "you have zero pounds". */
    @Test fun `no sources configured is a polite failure not a confident zero`() {
        val (key, _) = execute("money.total", emptyMap(), Wallet(emptyList()))
        assertEquals("failed", key)
    }

    @Test fun `every source refusing is also a polite failure`() {
        val (key, _) = execute("money.total", emptyMap(), Wallet(listOf(source("a", null))))
        assertEquals("failed", key)
    }

    /** Cents read aloud are noise. Grouping is what makes 12,340 land. */
    @Test fun `amounts are spoken in whole grouped units`() {
        assertEquals("12,340", formatMoney(12340.43))
        assertEquals("500", formatMoney(499.51))
    }

    // -- telling it a figure ------------------------------------------------

    @Test fun `a manual figure is stored`() {
        val phone = Wallet(emptyList())
        val (key, fields) = execute(
            "money.set", mapOf("account" to "Eurolife", "amount" to "12000"), phone
        )
        assertEquals("money.set", key)
        assertEquals(12000.0, phone.written["eurolife"]!!, 0.001)
        assertEquals("12,000", fields["amount"])
    }

    @Test fun `a manual figure written the greek way`() {
        val phone = Wallet(emptyList())
        execute("money.set", mapOf("account" to "eurolife", "amount" to "12.500,50"), phone)
        assertEquals(12500.50, phone.written["eurolife"]!!, 0.001)
    }

    @Test fun `no amount is a polite failure`() {
        assertEquals(
            "failed",
            execute("money.set", mapOf("account" to "eurolife"), Wallet(emptyList())).first,
        )
    }

    // -- reading a bank's own text -------------------------------------------

    @Test fun `a balance is read out of a transaction text`() {
        val body = "Card 1234: EUR 45.20 at LIDL. Available balance EUR 1,234.56"
        assertEquals(1234.56, SmsBalanceSource.parseBalance(body)!!.amount, 0.001)
    }

    @Test fun `a greek balance text`() {
        val body = "Χρεωση EUR 45,20. Διαθεσιμο υπολοιπο EUR 1.234,56"
        assertEquals(1234.56, SmsBalanceSource.parseBalance(body)!!.amount, 0.001)
    }

    @Test fun `the euro sign works as well as the code`() {
        assertEquals(980.0, SmsBalanceSource.parseBalance("Balance €980")!!.amount, 0.001)
    }

    /**
     * The whole failure mode. A transaction text names two numbers -- what you
     * spent and what is left -- and taking the wrong one produces a confidently
     * wrong balance, which is worse than no balance.
     */
    @Test fun `the amount spent is never mistaken for the balance`() {
        val body = "You spent EUR 45.20 at LIDL"
        assertNull(SmsBalanceSource.parseBalance(body))
    }

    @Test fun `a number far from the balance word is not taken`() {
        val body = "Balance enquiry completed. Reference 887766554433221100 EUR 45.20"
        assertNull(SmsBalanceSource.parseBalance(body))
    }

    @Test fun `an unrelated message yields nothing`() {
        assertNull(SmsBalanceSource.parseBalance("Your OTP is 445566"))
        assertNull(SmsBalanceSource.parseBalance(""))
    }

    /** `1.234,56` is Greek, `1,234.56` is English, and the same bank sends both. */
    @Test fun `numbers written either way round`() {
        val n = SmsBalanceSource::europeanNumber
        assertEquals(1234.56, n("1.234,56")!!, 0.001)
        assertEquals(1234.56, n("1,234.56")!!, 0.001)
        assertEquals(1234.0, n("1.234")!!, 0.001)     // lone separator, 3 digits: thousands
        assertEquals(1234.0, n("1,234")!!, 0.001)
        assertEquals(45.2, n("45.20")!!, 0.001)
        assertEquals(980.0, n("980")!!, 0.001)
        assertNull(n(""))
    }

    // -- routing -------------------------------------------------------------

    @Test fun `the question routes in both languages`() {
        val routes = buildRoutes { now }
        listOf("how much money do I have", "what's my balance", "how much have I got")
            .forEach { assertEquals(it, "money.total", route(it, routes)!!.action) }
        listOf("πόσα λεφτά έχω", "πόσα χρήματα έχω", "πόσα έχω")
            .forEach { assertEquals(it, "money.total", route(it, routes)!!.action) }
    }

    @Test fun `telling it a figure routes`() {
        val routes = buildRoutes { now }
        val match = route("βάλε το eurolife στα 12000", routes)!!
        assertEquals("money.set", match.action)
        assertEquals("eurolife", match.args["account"])
        assertEquals("12000", match.args["amount"])
    }

    /** A question must never overwrite a figure. */
    @Test fun `asking is not setting`() {
        val routes = buildRoutes { now }
        assertEquals("money.total", route("how much money do I have", routes)!!.action)
        assertEquals("money.total", route("πόσα λεφτά έχω", routes)!!.action)
    }
}
