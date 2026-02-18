package com.example.influxdemo.models;

public class MedicineRecord {
    private String time;
    private String name;
    private int stock;
    private double price;
    private String expiry; // keep as simple text like "2026-12-31"

    public MedicineRecord() {}

    public MedicineRecord(String time, String name, int stock, double price, String expiry) {
        this.time = time;
        this.name = name;
        this.stock = stock;
        this.price = price;
        this.expiry = expiry;
    }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getStock() { return stock; }
    public void setStock(int stock) { this.stock = stock; }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }

    public String getExpiry() { return expiry; }
    public void setExpiry(String expiry) { this.expiry = expiry; }
}
