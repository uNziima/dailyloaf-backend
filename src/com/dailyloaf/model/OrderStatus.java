package com.dailyloaf.model;

public enum OrderStatus {

    PENDING_PAYMENT,
    PAID,
    DELIVERED,
    CANCELLED,
    INVALID,
    AFTER_CUTOFF;
    
    public String sheetColour() {
        return switch (this) {
            case PENDING_PAYMENT -> "#fff3cd";
            case PAID            -> "#d4edda";
            case DELIVERED       -> "#cce5ff";
            case INVALID         -> "#f8d7da";
            case CANCELLED       -> "#e2e3e5";
            case AFTER_CUTOFF    -> "#ffeeba";
        };
    }
}