package com.particlesdevs.photoncamera.settings;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ItemOrderTest {
    private static final List<String> ITEMS = Arrays.asList("", "warm", "cool", "slide", "mine, v2.cube");

    @Test public void withoutAStoredOrderTheItemsStayAsListed() {
        assertEquals(ITEMS, ItemOrder.apply(ITEMS, null));
        assertEquals(ITEMS, ItemOrder.apply(ITEMS, ""));
    }

    @Test public void aMovedItemKeepsItsPlaceAndNoneStaysFirst() {
        final String stored = ItemOrder.move(ITEMS, 4, 1);
        assertEquals(Arrays.asList("", "mine, v2.cube", "warm", "cool", "slide"), ItemOrder.apply(ITEMS, stored));
        // a drop on «Нет» puts the card second, «Нет» never moves
        assertEquals(Arrays.asList("", "slide", "warm", "cool", "mine, v2.cube"), ItemOrder.apply(ITEMS, ItemOrder.move(ITEMS, 3, 0)));
        assertEquals(ItemOrder.text(ITEMS), ItemOrder.move(ITEMS, 0, 3));
        // to the end
        assertEquals(Arrays.asList("", "cool", "slide", "mine, v2.cube", "warm"), ItemOrder.apply(ITEMS, ItemOrder.move(ITEMS, 1, 4)));
    }

    @Test public void newItemsComeAfterTheKnownOnesAndGoneOnesAreDropped() {
        final String stored = "slide\ngone.cube\nwarm";
        assertEquals(Arrays.asList("", "slide", "warm", "cool", "mine, v2.cube"), ItemOrder.apply(ITEMS, stored));
    }
}
