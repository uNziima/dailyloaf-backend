package com.dailyloaf.model;

public class IncomingMessage {

    private final String from;
    private final String body;
    private final long   timestamp;

    public IncomingMessage(String from, String body, long timestamp) {
        this.from      = from;
        this.body      = body;
        this.timestamp = timestamp;
    }

    public String getFrom()      { return from; }
    public String getBody()      { return body; }
    public long   getTimestamp() { return timestamp; }

    public String getBodyNormalised() {
        return body == null ? "" : body.trim().toLowerCase();
    }

    @Override
    public String toString() {
        return "IncomingMessage{from='" + from + "', body='" + body + "'}";
    }
}
