import jdk.internal.misc.Unsafe;

public class Pid {
  public static void main(String[] args) throws Exception {
    for (int i = 0; i < 32; ++i) {
      new Thread(() -> {
        loop2();
      }).start();
    }
    loop2();
  }

  public static void loop2() {
    Unsafe u = Unsafe.getUnsafe();
    int old = u.getProcessorId();
    while (true) {
      int n = u.getProcessorId();
      if (old != n) {
        System.out.println("Changed " + old + " -> " + n);
        old = n;
      }
    }
  }

  public static void loop() {
    Unsafe u = Unsafe.getUnsafe();
    try {
      while (true) {
        run(u);
        Thread.sleep(100);
      }
    } catch (Exception e) {
    }
  }

  public static void run(Unsafe u) {
    System.out.println(Thread.currentThread() + " " + u.getProcessorId());
  }
}
