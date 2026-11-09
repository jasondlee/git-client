package io.github.jasondlee.gitclient.model;
public record ChangedFile(String path, String indexStatus, String worktreeStatus) {}
