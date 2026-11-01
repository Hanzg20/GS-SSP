package com.goldsky.ssp.payment

import org.junit.Assert.assertEquals
import org.junit.Test

class LoyaltyTest {
    private val s = LoyaltyRepository.Settings(enabled = true, points_per_dollar = 2, reward_points = 100, reward_cents = 500)

    @Test fun phoneLikeTheServer() {
        assertEquals("6135550101", LoyaltyRepository.normalizePhone("+1 (613) 555-0101"))
        assertEquals("6135550101", LoyaltyRepository.normalizePhone("613.555.0101"))
    }

    @Test fun rewardWholeRewardsWithinTheSale() {
        assertEquals(300 to 1500, LoyaltyRepository.reward(340, 5000, s))
        assertEquals(100 to 500, LoyaltyRepository.reward(340, 999, s))   // only one $5 fits in $9.99
        assertEquals(0 to 0, LoyaltyRepository.reward(99, 5000, s))
        assertEquals(0 to 0, LoyaltyRepository.reward(340, 400, s))
        assertEquals(0 to 0, LoyaltyRepository.reward(340, 5000, s.copy(enabled = false)))
    }

    @Test fun earnPerWholeDollar() {
        assertEquals(24, LoyaltyRepository.earnFor(1299, s))
        assertEquals(0, LoyaltyRepository.earnFor(99, s))
    }

    @Test fun rewardIsADiscountBeforeTax() {
        // $10 taxed + $10 exempt, $5 reward spread in proportion: tax on $7.50.
        val r = Pricing.price(listOf(Pricing.Line(1000, 1300), Pricing.Line(1000, 0)), reward = Discount("Points", amountCents = 500))
        assertEquals(500, r.rewardCents)
        assertEquals(1500, r.netCents)
        assertEquals(98, r.taxCents)
    }
}
