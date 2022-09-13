/*
 * @test /nodynamiccopyright/
 * @bug 8211102
 * @summary Ensure that the lambda analyzer does not run when --release 7 is specified,
 *          even if explicitly requested
 * @compile/fail/ref=AnalyzersCheckSourceLevel.out -Werror -XDfind=lambda -XDrawDiagnostics AnalyzersCheckSourceLevel.java
 *
 */
public class AnalyzersCheckSourceLevel {
    void t() {
        Runnable r = new Runnable() {
            @Override public void run() {}
        };
    }
}