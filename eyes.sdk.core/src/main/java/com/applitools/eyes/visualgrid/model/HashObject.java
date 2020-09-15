package com.applitools.eyes.visualgrid.model;

public class HashObject {
    private final String hashFormat;
    private final String hash;

    public HashObject(String hashFormat, String hash) {
        this.hashFormat = hashFormat;
        this.hash = hash;
    }

    public String getHashFormat() {
        return hashFormat;
    }

    public String getHash() {
        return hash;
    }
}
