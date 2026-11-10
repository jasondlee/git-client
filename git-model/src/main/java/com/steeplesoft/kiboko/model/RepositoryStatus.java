package com.steeplesoft.kiboko.model;
import java.util.List;
public record RepositoryStatus(String branch, List<ChangedFile> files) { public RepositoryStatus { files = List.copyOf(files); } }
