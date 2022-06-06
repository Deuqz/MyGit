package ru.hse.fmcs;

import java.nio.file.Path;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public class MyGitCommit {
    private final String author;
    private final String message;
    private final String branch;
    private final String hash;
    private final String parentHash;
    private final Date date;
    private final Map<Path, String> files;

    public MyGitCommit(String message, MyGitCommit parent, MyGitIndex index, String branch) {
        author = System.getProperty("user.name");
        this.message = message;
        this.branch = branch;
        Random random = new Random();
        hash = random.ints('0', 'z' + 1).filter(x -> x <= '9' || ('A' <= x && x <= 'Z') || 'a' <= x).limit(40).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        date = new Date();
        files = new HashMap<>();
        if (parent != null) {
            this.parentHash = parent.getHash();
            files.putAll(parent.getFiles());
        } else {
            this.parentHash = null;
        }
        files.putAll(index.getNewFiles());
        files.putAll(index.getModifiedFiles());
        for (Path path : index.getDeletedFiles()) {
            files.remove(path);
        }
    }

    public String getAuthor() {
        return author;
    }

    public String getHash() {
        return hash;
    }

    public String getParentHash() {
        return parentHash;
    }

    public Map<Path, String> getFiles() {
        return files;
    }

    public String getBranch() {
        return branch;
    }

    public Date getDate() {
        return date;
    }

    public String getMessage() {
        return message;
    }

    public boolean containsName(Path pathToFile) {
        return files.containsKey(pathToFile);
    }

    public boolean containsFile(Path pathToFile, String hash) {
        return files.containsKey(pathToFile) && files.get(pathToFile).equals(hash);
    }
}
