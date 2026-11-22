package com.lab.camel.model;

public record Order(long orderId, String item, Integer qty) {

    public Order {
        if (qty == null) {
            qty = 1;
        }
    }
}