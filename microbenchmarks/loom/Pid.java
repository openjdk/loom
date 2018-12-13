import jdk.internal.misc.Unsafe;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;

public class Pid {
  public static HashSet<Integer> SEEN = new HashSet<Integer>();

  public static void main(String[] args) throws Exception {
    Thread[] threads = new Thread[32];
    for (int i = 0; i < 32; ++i) {
      threads[i] = new Thread(() -> {
        loop2();
      });

      threads[i].start();

    }
    loop2();

    for (int i = 0; i < 32; ++i) {
      threads[i].join();
    }

    List<Integer> lst = new ArrayList<Integer>(SEEN);
    for (int v : lst) {
      System.out.println(v);
    }

  }

  public static void seen(int value) {
    synchronized(SEEN) {
      SEEN.add(value);
    }
  }

  public static void loop2() {
    Unsafe u = Unsafe.getUnsafe();
    int old = u.getProcessorId();
    seen(old);
    long start = System.currentTimeMillis();
    while (System.currentTimeMillis() - start < 10000) {
      int n = u.getProcessorId();
      if (old != n) {
        seen(n);
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
