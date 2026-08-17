import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class DiagnosticFixture {
  private static final Object FIRST_LOCK = new Object();
  private static final Object SECOND_LOCK = new Object();
  private static final CountDownLatch FIRST_HELD = new CountDownLatch(1);
  private static final CountDownLatch SECOND_HELD = new CountDownLatch(1);
  private static final CountDownLatch NEVER_RELEASE = new CountDownLatch(1);
  private static final List<byte[]> RETAINED = new ArrayList<>();

  private DiagnosticFixture() {}

  public static void main(String[] args) throws Exception {
    for (int index = 0; index < 16; index++) {
      RETAINED.add(new byte[1024 * 1024]);
    }

    for (int index = 0; index < 4; index++) {
      Thread thread = new Thread(() -> await(NEVER_RELEASE), "phase18-leak-fixture-" + index);
      thread.start();
    }

    Thread first =
        new Thread(
            () -> {
              synchronized (FIRST_LOCK) {
                FIRST_HELD.countDown();
                await(SECOND_HELD);
                synchronized (SECOND_LOCK) {
                  // unreachable: deterministic fixture deadlock
                }
              }
            },
            "phase18-deadlock-first");
    Thread second =
        new Thread(
            () -> {
              synchronized (SECOND_LOCK) {
                SECOND_HELD.countDown();
                await(FIRST_HELD);
                synchronized (FIRST_LOCK) {
                  // unreachable: deterministic fixture deadlock
                }
              }
            },
            "phase18-deadlock-second");
    first.start();
    second.start();
    FIRST_HELD.await();
    SECOND_HELD.await();
    System.out.println("PHASE18_DIAGNOSTIC_FIXTURE_READY");
    System.out.flush();
    NEVER_RELEASE.await();
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
  }
}
