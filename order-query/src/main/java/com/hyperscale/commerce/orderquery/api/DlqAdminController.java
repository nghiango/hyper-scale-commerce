package com.hyperscale.commerce.orderquery.api;

import com.hyperscale.commerce.orderquery.application.DlqReplayResult;
import com.hyperscale.commerce.orderquery.application.DlqReplayService;
import java.util.concurrent.ExecutionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/admin/dlq")
public class DlqAdminController {
  private final DlqReplayService dlqReplayService;

  public DlqAdminController(DlqReplayService dlqReplayService) {
    this.dlqReplayService = dlqReplayService;
  }

  @PostMapping("/replay")
  public ResponseEntity<DlqReplayResult> replay(@RequestBody DlqReplayRequest request)
      throws ExecutionException, InterruptedException {
    if (request.dlqTopic().isBlank()) {
      throw new IllegalArgumentException("dlqTopic must not be blank");
    }
    if (request.targetTopic().isBlank()) {
      throw new IllegalArgumentException("targetTopic must not be blank");
    }
    if (request.maxRecords() <= 0) {
      throw new IllegalArgumentException("maxRecords must be greater than 0");
    }

    DlqReplayResult result =
        dlqReplayService.replay(request.dlqTopic(), request.targetTopic(), request.maxRecords());
    return ResponseEntity.ok(result);
  }
}

record DlqReplayRequest(String dlqTopic, String targetTopic, Integer maxRecords) {
  DlqReplayRequest {
    if (maxRecords == null) {
      maxRecords = 100;
    }
  }
}
