package com.notio.desktop

import com.notio.common.ButtonIcons
import kotlin.test.Test
import kotlin.test.assertEquals

/** Android D311: Quantity and Price of a list line are changed by + / − / =, so their icon is the budget's, not the pencil. */
class ButtonIconsTest {
    @Test
    fun theNumbersOfAListLineHaveThePlusMinusIcon() {
        assertEquals("PlusMinus", ButtonIcons.nameFor("list_change_quantity"))
        assertEquals("PlusMinus", ButtonIcons.nameFor("list_change_price"))
        assertEquals(PlusMinusIcon, AppIcons.byName("PlusMinus"))
    }
}
