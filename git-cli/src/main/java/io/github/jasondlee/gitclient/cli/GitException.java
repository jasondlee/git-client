package io.github.jasondlee.gitclient.cli;
public class GitException extends RuntimeException { public GitException(String message) { super(message); } public GitException(String message, Throwable cause) { super(message, cause); } }
