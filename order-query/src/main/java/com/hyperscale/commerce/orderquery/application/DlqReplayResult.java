package com.hyperscale.commerce.orderquery.application;

public record DlqReplayResult(
    String dlqTopic, String targetTopic, int replayedCount, int skippedCount) {}
