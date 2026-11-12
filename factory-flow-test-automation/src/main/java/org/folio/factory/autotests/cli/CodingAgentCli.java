package org.folio.factory.autotests.cli;

import java.nio.file.Path;
import java.util.List;

/** One headless coding-agent session in a working directory. A seam so tests never start a real CLI. */
public interface CodingAgentCli {

    /**
     * @param allowedTools the CLI's tool allowlist; everything else is denied
     * @return the session's final text output
     */
    String run(String prompt, Path workDir, List<String> allowedTools);
}
