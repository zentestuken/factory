package org.folio.factory.autotests.model;

import java.util.List;

public record RelatedFiles(List<File> files) {

    public RelatedFiles {
        files = files == null ? List.of() : files;
    }

    public record File(
            String path,
            String content){
    }
}
