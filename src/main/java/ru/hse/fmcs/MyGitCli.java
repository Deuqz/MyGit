package ru.hse.fmcs;

import org.jetbrains.annotations.NotNull;

import java.io.PrintStream;
import java.util.List;

public class MyGitCli implements GitCli{
    private MyGitRepository repository;

    public MyGitCli(String directory) throws GitException {
        repository = new MyGitRepository(directory);
    }

    @Override
    public void runCommand(@NotNull String command, @NotNull List<@NotNull String> arguments) throws GitException {
        switch (command) {
            case GitConstants.INIT:
                repository.init();
                break;
            case GitConstants.STATUS:
                repository.status();
                break;
            case GitConstants.ADD:
                repository.add(arguments);
                break;
            case GitConstants.CHECKOUT:
                repository.checkout(arguments);
                break;
            case GitConstants.COMMIT:
                repository.commit(arguments.get(0));
                break;
            case GitConstants.LOG:
                repository.log(arguments.isEmpty() ? null : arguments.get(0));
                break;
            case GitConstants.RESET:
                repository.reset(arguments.get(0));
                break;
            case GitConstants.RM:
                repository.rm(arguments);
                break;
            case GitConstants.BRANCH_CREATE:
                repository.branchCreate(arguments.get(0));
                break;
            case GitConstants.SHOW_BRANCHES:
                repository.showBranches();
                break;
            case GitConstants.BRANCH_REMOVE:
                repository.branchRemove(arguments.get(0));
                break;
        }
    }

    @Override
    public void setOutputStream(@NotNull PrintStream outputStream) {
        repository.setOutputStream(outputStream);
    }

    @Override
    public @NotNull String getRelativeRevisionFromHead(int n) throws GitException {
        return repository.getRelativeRevisionFromHead(n);
    }
}
