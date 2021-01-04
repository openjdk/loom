package jdk.test.failurehandler;

import java.nio.file.Path;

public interface CoreInfoGatherer {
    void gatherCoreInfo(HtmlSection section, Path core);
}
