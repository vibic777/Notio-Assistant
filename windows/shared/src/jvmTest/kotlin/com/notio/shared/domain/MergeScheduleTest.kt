package com.notio.shared.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneOffset

/** The times of an income / expense that repeats (Android D339) and the case of the names in a merge (D340). */
class MergeScheduleTest {
    private val zone = ZoneOffset.UTC
    private fun at(y: Int, m: Int, d: Int, h: Int = 9, min: Int = 30) = LocalDateTime.of(y, m, d, h, min).toInstant(zone).toEpochMilli()
    private fun next(rule: MergeRepeat, at: Long, after: Long = at, days: Int = 0, monthDay: Int = 0) =
        MergeSchedule.next(rule, days, monthDay, at, after, zone)

    @Test
    fun `once has no next time, every day is the next day at the same time`() {
        assertNull(next(MergeRepeat.NONE, at(2026, 10, 9)))
        assertEquals(at(2026, 10, 10), next(MergeRepeat.DAILY, at(2026, 10, 9)))
        // Counted from any moment: the first one after it.
        assertEquals(at(2026, 10, 13), next(MergeRepeat.DAILY, at(2026, 10, 9), after = at(2026, 10, 12, 10)))
        assertEquals(at(2026, 10, 12), next(MergeRepeat.DAILY, at(2026, 10, 9), after = at(2026, 10, 12, 9, 29)))
        assertEquals(at(2027, 1, 1), next(MergeRepeat.DAILY, at(2026, 12, 31)))
    }

    @Test
    fun `every week comes on the chosen days, or on the weekday of its time when none is chosen`() {
        val friday = at(2026, 10, 9)
        val monWedFri = RecurrenceRules.maskOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)
        val monday = next(MergeRepeat.WEEKLY, friday, days = monWedFri)!!
        assertEquals(at(2026, 10, 12), monday)
        val wednesday = next(MergeRepeat.WEEKLY, monday, days = monWedFri)!!
        assertEquals(at(2026, 10, 14), wednesday)
        assertEquals(at(2026, 10, 16), next(MergeRepeat.WEEKLY, wednesday, days = monWedFri))
        assertEquals(at(2026, 10, 16), next(MergeRepeat.WEEKLY, friday))
        assertEquals(at(2026, 10, 11), next(MergeRepeat.WEEKLY, friday, days = RecurrenceRules.bit(DayOfWeek.SUNDAY)))
    }

    @Test
    fun `every month keeps its day, and a month without it takes its last day`() {
        assertEquals(at(2026, 11, 15), next(MergeRepeat.MONTHLY, at(2026, 10, 15)))
        assertEquals(at(2027, 1, 15), next(MergeRepeat.MONTHLY, at(2026, 12, 15)))
        // The 31st: 28 February, then the 31st again — the day is kept apart from the last time.
        val february = next(MergeRepeat.MONTHLY, at(2026, 1, 31), monthDay = 31)!!
        assertEquals(at(2026, 2, 28), february)
        val march = next(MergeRepeat.MONTHLY, february, monthDay = 31)!!
        assertEquals(at(2026, 3, 31), march)
        assertEquals(at(2026, 4, 30), next(MergeRepeat.MONTHLY, march, monthDay = 31))
        assertEquals(at(2028, 2, 29), next(MergeRepeat.MONTHLY, at(2028, 1, 31), monthDay = 31))
        // Missed months are found one by one from the time that was due.
        assertEquals(at(2026, 6, 30), next(MergeRepeat.MONTHLY, at(2026, 1, 31), after = at(2026, 5, 31), monthDay = 31))
    }

    @Test
    fun `every year keeps its date, 29 February is 28 February in other years`() {
        assertEquals(at(2027, 10, 9), next(MergeRepeat.YEARLY, at(2026, 10, 9)))
        val y2029 = next(MergeRepeat.YEARLY, at(2028, 2, 29), monthDay = 29)!!
        assertEquals(at(2029, 2, 28), y2029)
        assertEquals(at(2030, 2, 28), next(MergeRepeat.YEARLY, y2029, monthDay = 29))
        assertEquals(at(2032, 2, 29), next(MergeRepeat.YEARLY, at(2031, 2, 28), monthDay = 29))
    }

    private var ids = 0
    private fun node(id: String, parent: String?, title: String, sum: Long = 0, group: Boolean = false, note: String = "acc") = BudgetNode(
        id = id, noteId = note, parentId = parent, title = title, kind = if (group) BudgetNodeKind.GROUP else BudgetNodeKind.LEAF,
        manualSumMinor = sum, manualGoalMinor = 0, comment = null, cachedSumMinor = 0, cachedGoalMinor = 0, cachedRemainderMinor = 0,
        isExpanded = true, sortOrder = 0, createdAt = 0, updatedAt = 0,
    )

    // The account: Total > Home > Касса 100. The income: Total > home > касса 50.
    private val account = listOf(node("r", null, "", group = true), node("g", "r", "Home", group = true), node("c", "g", "Касса", sum = 100))
    private val income = listOf(
        node("sr", null, "", group = true, note = "inc"), node("sg", "sr", "home", group = true, note = "inc"), node("sc", "sg", " касса ", sum = 50, note = "inc"),
    )

    private fun plan(options: MergeOptions) = BudgetMergePlanner.plan(income, account, options, { "new" + ids++ }, now = 1)
    private fun leaves(plan: MergePlan) = plan.nodes.filter { it.kind == BudgetNodeKind.LEAF }.associate { it.title to it.manualSumMinor }
    private fun groups(plan: MergePlan) = plan.nodes.filter { it.kind == BudgetNodeKind.GROUP && it.parentId != null }.map { it.title }

    @Test
    fun `names that differ by case are one item, or two when the case counts`() {
        assertEquals(mapOf("Касса" to 150L), leaves(plan(MergeOptions(sign = 1))))
        val apart = plan(MergeOptions(sign = 1, ignoreItemCase = false))
        assertEquals(mapOf("Касса" to 100L, "касса" to 50L), leaves(apart))
        assertEquals(1, apart.created)
        // The same case is one item also when the case counts; the spaces around never count.
        val same = BudgetMergePlanner.plan(
            listOf(income[0], income[2].copy(parentId = "sr", title = " Касса ")), account, MergeOptions(sign = 1, ignoreItemCase = false), { "x" }, now = 1,
        )
        assertEquals(mapOf("Касса" to 150L), leaves(same))
    }

    @Test
    fun `by path the groups are one group, or two when their case counts`() {
        val together = plan(MergeOptions(sign = 1, byPath = true))
        assertEquals(mapOf("Касса" to 150L), leaves(together))
        assertEquals(listOf("Home"), groups(together))
        // The case of the groups counts: "home" is another group, made in the account, and the item goes into it.
        val apart = plan(MergeOptions(sign = 1, byPath = true, ignoreGroupCase = false))
        assertEquals(mapOf("Касса" to 100L, "касса" to 50L), leaves(apart))
        assertEquals(listOf("Home", "home"), groups(apart))
        // Only the case of the items counts: a second item in the same group.
        val items = plan(MergeOptions(sign = 1, byPath = true, ignoreItemCase = false))
        assertEquals(listOf("Home"), groups(items))
        assertEquals("g", items.nodes.single { it.title == "касса" }.parentId)
        assertEquals(mapOf("Касса" to 100L, "касса" to 50L), leaves(items))
    }
}
